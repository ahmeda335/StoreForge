package dev.storeforge.developer;

import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.Actor;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

/**
 * The authenticated caller of the developer API: one credential, bound to one store, with scopes.
 * Authorities are {@code SCOPE_<code>} (Spring Security's convention for OAuth scopes).
 */
public record ApiClientPrincipal(UUID credentialId, StoreId storeId, Actor.ApiClient.Kind kind, String credentialName,
                                 Set<Scope> scopes) implements OAuth2AuthenticatedPrincipal {

    public ApiClientPrincipal {
        scopes = Set.copyOf(scopes);
    }

    public boolean has(Scope scope) {
        return scopes.contains(scope);
    }

    public Actor.ApiClient actor() {
        return new Actor.ApiClient(credentialId, storeId, kind, Scope.permissionsOf(scopes));
    }

    @Override
    public Map<String, Object> getAttributes() {
        return Map.of("credential_id", credentialId.toString(), "store_id", storeId.toString(), "kind", kind.name());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return scopes.stream().map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope.code())).toList();
    }

    @Override
    public String getName() {
        return credentialId.toString();
    }
}
