package dev.storeforge.identity.internal.web;

import dev.storeforge.identity.internal.application.EmailVerificationService;
import dev.storeforge.identity.internal.application.IdentityExceptions;
import dev.storeforge.identity.internal.application.IssuedTokens;
import dev.storeforge.identity.internal.application.LoginService;
import dev.storeforge.identity.internal.application.PasswordResetService;
import dev.storeforge.identity.internal.application.RegistrationService;
import dev.storeforge.identity.internal.application.TokenService;
import dev.storeforge.identity.internal.security.AuthProperties;
import dev.storeforge.identity.internal.security.JwtKeys;
import dev.storeforge.identity.internal.web.AuthDtos.AcceptedResponse;
import dev.storeforge.identity.internal.web.AuthDtos.EmailRequest;
import dev.storeforge.identity.internal.web.AuthDtos.LoginRequest;
import dev.storeforge.identity.internal.web.AuthDtos.PasswordResetConfirmation;
import dev.storeforge.identity.internal.web.AuthDtos.RegisterRequest;
import dev.storeforge.identity.internal.web.AuthDtos.TokenRequest;
import dev.storeforge.identity.internal.web.AuthDtos.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication API. Controllers only translate HTTP ↔ use cases; rules live in the
 * application services.
 */
@RestController
@RequestMapping("/auth/v1")
@RequiredArgsConstructor
class AuthController {

    private static final AcceptedResponse CHECK_INBOX =
            new AcceptedResponse("If the address can receive it, an email is on its way.");

    private final RegistrationService registration;
    private final LoginService login;
    private final TokenService tokens;
    private final EmailVerificationService emailVerification;
    private final PasswordResetService passwordReset;
    private final RefreshTokenCookies cookies;
    private final JwtKeys keys;
    private final AuthProperties properties;

    @PostMapping("/registrations")
    ResponseEntity<AcceptedResponse> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        registration.register(body.email(), body.password(), request.getRemoteAddr());
        return ResponseEntity.accepted().body(CHECK_INBOX);
    }

    @PostMapping("/token")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return tokenResponse(login.login(body.email(), body.password(), request.getRemoteAddr()));
    }

    /**
     * Cookie-authenticated, so it needs CSRF defenses: SameSite=Strict on the cookie, an Origin
     * allow-list, and a custom header. A cross-site form or {@code <img>} cannot set custom headers,
     * and a cross-site fetch() that sets one triggers a CORS preflight, which we don't allow.
     */
    @PostMapping("/token/refresh")
    ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) @Nullable String refreshToken,
            @RequestHeader(name = "X-Requested-With", required = false) @Nullable String requestedWith,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) @Nullable String origin) {
        rejectCrossSite(requestedWith, origin);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IdentityExceptions.InvalidRefreshToken();
        }
        return tokenResponse(tokens.refresh(refreshToken));
    }

    @PostMapping("/token/revocation")
    ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) @Nullable String refreshToken,
            @RequestHeader(name = "X-Requested-With", required = false) @Nullable String requestedWith,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) @Nullable String origin) {
        rejectCrossSite(requestedWith, origin);
        if (refreshToken != null && !refreshToken.isBlank()) {
            tokens.logout(refreshToken);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }

    @PostMapping("/email-verifications")
    ResponseEntity<AcceptedResponse> resendVerification(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        emailVerification.resend(body.email(), request.getRemoteAddr());
        return ResponseEntity.accepted().body(CHECK_INBOX);
    }

    @PostMapping("/email-verifications/confirmation")
    ResponseEntity<Void> confirmEmail(@Valid @RequestBody TokenRequest body, HttpServletRequest request) {
        emailVerification.confirm(body.token(), request.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-resets")
    ResponseEntity<AcceptedResponse> requestPasswordReset(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        passwordReset.request(body.email(), request.getRemoteAddr());
        return ResponseEntity.accepted().body(CHECK_INBOX);
    }

    @PostMapping("/password-resets/confirmation")
    ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmation body, HttpServletRequest request) {
        passwordReset.confirm(body.token(), body.newPassword(), request.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    /** Public verification keys (JWKS), for anything that needs to verify our access tokens. */
    @GetMapping("/jwks.json")
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
                .body(keys.verificationKeys().toJSONObject(true));
    }

    private void rejectCrossSite(@Nullable String requestedWith, @Nullable String origin) {
        boolean originAllowed = origin == null || properties.allowedOrigins().contains(origin);
        if (!"XMLHttpRequest".equals(requestedWith) || !originAllowed) {
            throw new IdentityExceptions.CrossSiteRequestRejected();
        }
    }

    private ResponseEntity<TokenResponse> tokenResponse(IssuedTokens issued) {
        return ResponseEntity.status(HttpStatus.OK)
                .cacheControl(CacheControl.noStore())   // RFC 6749: token responses must not be cached
                .header(HttpHeaders.SET_COOKIE, cookies.issue(issued.refreshToken()).toString())
                .body(new TokenResponse(issued.accessToken().value(), "Bearer", issued.accessToken().expiresInSeconds()));
    }
}
