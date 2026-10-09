package dev.storeforge.identity.internal.web;

import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.identity.internal.platform.PlatformMfaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Step up from a merchant session (password) to a platform session (password + second factor).
 * Every response here contains a secret: never cached.
 */
@RestController
@RequestMapping("/platform/v1")
@RequiredArgsConstructor
class PlatformSessionController {

    private final PlatformMfaService mfa;

    record CodeRequest(@NotBlank @Size(max = 20) String code) {
    }

    record SessionRequest(@Nullable @Size(max = 20) String code, @Nullable @Size(max = 20) String recoveryCode) {
    }

    @PostMapping("/mfa/enrollment")
    ResponseEntity<Map<String, String>> enroll(@AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        var enrollment = mfa.enroll(user.userId(), request.getRemoteAddr());
        return noStore(Map.of("secret", enrollment.secret(), "otpauthUri", enrollment.otpauthUri()));
    }

    @PostMapping("/mfa/enrollment/confirmation")
    ResponseEntity<Map<String, List<String>>> confirm(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody CodeRequest body,
                                                      HttpServletRequest request) {
        return noStore(Map.of("recoveryCodes", mfa.confirm(user.userId(), body.code(), request.getRemoteAddr())));
    }

    @PostMapping("/session")
    ResponseEntity<Map<String, Object>> session(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody SessionRequest body,
                                                HttpServletRequest request) {
        if ((body.code() == null) == (body.recoveryCode() == null)) {
            throw new dev.storeforge.shared.error.InvalidParameterException("code/recoveryCode", "exactly one of them");
        }
        var session = mfa.startSession(user.userId(), body.code(), body.recoveryCode(), request.getRemoteAddr());
        return noStore(Map.of("access_token", session.accessToken(), "token_type", "Bearer", "expires_in", session.expiresInSeconds(),
                "amr", session.amr()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
