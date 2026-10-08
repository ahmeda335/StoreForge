package dev.storeforge.developer.internal.credentials;

import dev.storeforge.developer.ApiClientPrincipal;
import dev.storeforge.shared.crypto.SecretTokens;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.Actor;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Component;

/**
 * Opaque bearer tokens are looked up on EVERY request (SHA-256 → unique index). That costs one
 * indexed query, and buys immediate revocation: a revoked key or uninstalled app fails on its very
 * next call. A JWT would be valid until it expires, whatever we do.
 */
@Component
@RequiredArgsConstructor
public class ApiCredentialIntrospector implements OpaqueTokenIntrospector {

    private final ApiCredentialStore credentials;
    private final Clock clock;

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        if (!ApiTokens.looksValid(token)) {
            throw new BadOpaqueTokenException("Invalid token");
        }
        ApiCredentialStore.Credential credential = credentials.findActiveByHash(SecretTokens.hash(token))
                .orElseThrow(() -> new BadOpaqueTokenException("Invalid token"));
        credentials.touch(credential.id(), clock.instant());
        return new ApiClientPrincipal(credential.id(), StoreId.of(credential.storeId()),
                "API_KEY".equals(credential.kind()) ? Actor.ApiClient.Kind.API_KEY : Actor.ApiClient.Kind.APP,
                credential.name(), credential.scopes());
    }
}
