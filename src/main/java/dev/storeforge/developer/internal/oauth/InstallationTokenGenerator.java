package dev.storeforge.developer.internal.oauth;

import dev.storeforge.shared.error.StoreForgeException;
import dev.storeforge.stores.StoreDirectory;
import dev.storeforge.stores.StoreInfo;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.stereotype.Component;

/**
 * The token endpoint's moment of truth: exchanging an authorization code INSTALLS the app on the
 * store the merchant approved (the {@code store} request parameter), and the access token is the
 * installation's credential. No refresh tokens (offline, installation-bound tokens).
 */
@Component
@RequiredArgsConstructor
class InstallationTokenGenerator implements OAuth2TokenGenerator<OAuth2AccessToken> {

    static final String STORE_PARAMETER = "store";

    private final InstallationService installations;
    private final StoreDirectory stores;

    @Override
    public @Nullable OAuth2AccessToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                || !AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())) {
            return null;
        }
        OAuth2AuthorizationRequest request = context.getAuthorization().getAttribute(OAuth2AuthorizationRequest.class.getName());
        Object host = request == null ? null : request.getAdditionalParameters().get(STORE_PARAMETER);
        StoreInfo store = host == null ? null : stores.resolveHost(host.toString()).orElse(null);
        if (store == null) {
            throw error(OAuth2ErrorCodes.INVALID_REQUEST, "Unknown or missing store");
        }
        try {
            var installed = installations.install(UUID.fromString(context.getRegisteredClient().getId()), store.id(),
                    UUID.fromString(context.getPrincipal().getName()), InstallationService.scopes(context.getAuthorizedScopes()));
            Instant now = Instant.now();
            return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, installed.accessToken(), now,
                    now.plus(context.getRegisteredClient().getTokenSettings().getAccessTokenTimeToLive()), context.getAuthorizedScopes());
        } catch (StoreForgeException e) {
            // The merchant lost the right to grant this between consent and exchange (or never had it).
            throw error(OAuth2ErrorCodes.INVALID_GRANT, "The approving user can no longer grant these scopes on this store");
        }
    }

    private static OAuth2AuthenticationException error(String code, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, description, null));
    }
}
