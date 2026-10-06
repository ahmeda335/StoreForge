package dev.storeforge.identity.internal.web;

import dev.storeforge.identity.internal.security.AuthProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token lives in a cookie the browser's JavaScript cannot read:
 * <ul>
 *   <li>{@code HttpOnly}: an XSS bug cannot steal it (the access token, kept in memory, could
 *       still be abused while the XSS runs, but only for its 15-minute lifetime);</li>
 *   <li>{@code Secure}: never sent over plain HTTP (browsers treat localhost as secure);</li>
 *   <li>{@code SameSite=Strict}: not attached to requests initiated by other sites (CSRF);</li>
 *   <li>{@code Path=/auth/v1/token}: only sent to the refresh/logout endpoints, never to the rest of the API.</li>
 * </ul>
 */
@Component
class RefreshTokenCookies {

    static final String NAME = "sf_refresh";
    static final String PATH = "/auth/v1/token";

    private final Duration maxAge;

    RefreshTokenCookies(AuthProperties properties) {
        this.maxAge = properties.refreshTokenTtl();
    }

    ResponseCookie issue(String refreshToken) {
        return base(refreshToken).maxAge(maxAge).build();
    }

    ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Strict").path(PATH);
    }
}
