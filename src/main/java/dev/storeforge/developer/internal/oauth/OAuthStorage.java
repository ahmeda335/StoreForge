package dev.storeforge.developer.internal.oauth;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/** How Spring Authorization Server's state is kept (or deliberately not kept). */
final class OAuthStorage {

    private OAuthStorage() {
    }

    /**
     * Pending authorizations live in PostgreSQL (shared by all instances: the code may be issued by
     * one and exchanged on another). The moment a token is issued, the record is DELETED instead of
     * updated: the stock implementation would store the access token in plaintext, and we keep
     * tokens only as SHA-256 hashes (api_credentials). Deleting also makes the code single-use: a
     * replayed code finds nothing.
     */
    static class Authorizations implements OAuth2AuthorizationService {

        private final JdbcOAuth2AuthorizationService jdbc;

        Authorizations(JdbcOperations jdbcOperations, RegisteredClientRepository clients) {
            this.jdbc = new JdbcOAuth2AuthorizationService(jdbcOperations, clients);
        }

        @Override
        public void save(OAuth2Authorization authorization) {
            if (authorization.getAccessToken() != null) {
                jdbc.remove(authorization);
            } else {
                jdbc.save(authorization);
            }
        }

        @Override
        public void remove(OAuth2Authorization authorization) {
            jdbc.remove(authorization);
        }

        @Override
        public @Nullable OAuth2Authorization findById(String id) {
            return jdbc.findById(id);
        }

        @Override
        public @Nullable OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
            return jdbc.findByToken(token, tokenType);
        }
    }

    /**
     * Never remembers a consent: installing (or reinstalling, possibly with new scopes, for possibly
     * another store) always shows the consent page. The merchant sees exactly what they grant, every time.
     */
    static class NoRememberedConsent implements OAuth2AuthorizationConsentService {

        @Override
        public void save(OAuth2AuthorizationConsent authorizationConsent) {
        }

        @Override
        public void remove(OAuth2AuthorizationConsent authorizationConsent) {
        }

        @Override
        public @Nullable OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
            return null;
        }
    }
}
