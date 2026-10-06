package dev.storeforge.identity.internal.application;

import dev.storeforge.identity.internal.security.AccessTokenIssuer.AccessToken;

/** Result of login/refresh: the access token goes in the response body, the refresh token in a cookie. */
public record IssuedTokens(AccessToken accessToken, String refreshToken) {

    @Override
    public String toString() {
        return "IssuedTokens[REDACTED]";
    }
}
