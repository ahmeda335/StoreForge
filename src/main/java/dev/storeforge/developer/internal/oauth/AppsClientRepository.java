package dev.storeforge.developer.internal.oauth;

import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

/**
 * Our registered apps, as Spring Authorization Server sees them. Every app is a CONFIDENTIAL client
 * (secret at the token endpoint), uses only the authorization code grant, MUST use PKCE (RFC 9700
 * recommends it even for confidential clients), and always asks the merchant for consent. Codes
 * live 5 minutes; access tokens are opaque "offline" tokens that last until the app is uninstalled.
 */
@Component
@RequiredArgsConstructor
class AppsClientRepository implements RegisteredClientRepository {

    private final OAuthAppStore apps;

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Apps are registered through the developer API");
    }

    @Override
    public @Nullable RegisteredClient findById(String id) {
        try {
            return apps.findById(UUID.fromString(id)).map(AppsClientRepository::toClient).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public @Nullable RegisteredClient findByClientId(String clientId) {
        return apps.findByClientId(clientId).map(AppsClientRepository::toClient).orElse(null);
    }

    private static RegisteredClient toClient(OAuthAppStore.App app) {
        return RegisteredClient.withId(app.id().toString())
                .clientId(app.clientId())
                .clientSecret(app.clientSecretHash())
                .clientName(app.name())
                .clientIdIssuedAt(app.createdAt())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUris(uris -> uris.addAll(app.redirectUris()))
                .scopes(scopes -> scopes.addAll(app.scopes()))
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
                .tokenSettings(TokenSettings.builder()
                        .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        .accessTokenTimeToLive(Duration.ofDays(3650))
                        .build())
                .build();
    }
}
