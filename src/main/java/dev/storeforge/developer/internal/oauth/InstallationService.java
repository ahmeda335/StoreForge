package dev.storeforge.developer.internal.oauth;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.developer.ApiCredentialRevoked;
import dev.storeforge.developer.Scope;
import dev.storeforge.developer.internal.credentials.ApiCredentialStore;
import dev.storeforge.developer.internal.credentials.ApiTokens;
import dev.storeforge.shared.crypto.SecretTokens;
import dev.storeforge.shared.error.StoreForgeException;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * App installations: a store + an app + the scopes a merchant approved, with an opaque access
 * token ({@code sfat_}) stored as an api_credentials row. Created at the OAuth token exchange;
 * removed by uninstalling, which revokes the token at once and deletes the app's webhook
 * subscriptions in the same transaction.
 */
@Service
@RequiredArgsConstructor
public class InstallationService {

    static final String KIND = "APP_INSTALLATION";

    private final ApiCredentialStore credentials;
    private final OAuthAppStore apps;
    private final StoreAccess access;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final Clock clock;

    public record Installed(UUID installationId, StoreId storeId, String accessToken, Set<Scope> scopes) {
    }

    public record Installation(UUID id, UUID appId, String appName, List<String> scopes, UUID installedBy, Instant installedAt,
                               UUID credentialId) {
    }

    /**
     * Re-checks, at the moment the token is issued, that the approving merchant may grant this: a
     * check on the consent page alone could be bypassed by posting the consent form directly.
     * Installing again replaces the previous installation (new token, old one revoked).
     */
    public Installed install(UUID appId, StoreId storeId, UUID userId, Set<Scope> scopes) {
        OAuthAppStore.App app = apps.findById(appId).orElseThrow();
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.APPS_MANAGE);
            access.require(userId, storeId, Scope.permissionsOf(scopes).toArray(Permission[]::new));
            jdbc.sql("""
                    SELECT id, credential_id FROM app_installations WHERE store_id = :store AND app_id = :app AND uninstalled_at IS NULL
                    FOR UPDATE
                    """)
                    .param("store", storeId.value()).param("app", appId)
                    .query((rs, n) -> new UUID[] {rs.getObject("id", UUID.class), rs.getObject("credential_id", UUID.class)})
                    .optional().ifPresent(previous -> end(storeId, previous[0], previous[1]));
            Instant now = clock.instant();
            UUID credentialId = Ids.newId();
            String token = ApiTokens.newToken(ApiTokens.APP_TOKEN_PREFIX);
            credentials.insert(credentialId, storeId, KIND, app.name(), ApiTokens.displayPrefix(token), SecretTokens.hash(token), scopes,
                    userId, now);
            UUID installationId = Ids.newId();
            jdbc.sql("""
                    INSERT INTO app_installations (id, store_id, app_id, credential_id, scopes, installed_by, installed_at)
                    VALUES (:id, :store, :app, :credential, CAST(:scopes AS text[]), :user, :now)
                    """)
                    .param("id", installationId).param("store", storeId.value()).param("app", appId).param("credential", credentialId)
                    .param("scopes", scopes.stream().map(Scope::code).sorted().toArray(String[]::new)).param("user", userId)
                    .param("now", Timestamp.from(now)).update();
            audit.recordUserAction(storeId, userId, "app.installed", "app", appId.toString(),
                    Map.of("app", app.name(), "scopes", scopes.stream().map(Scope::code).sorted().toList()));
            return new Installed(installationId, storeId, token, scopes);
        });
    }

    public List<Installation> list(UUID userId, StoreId storeId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.APPS_MANAGE);
            return jdbc.sql("""
                    SELECT i.id, i.app_id, a.name, i.scopes, i.installed_by, i.installed_at, i.credential_id
                      FROM app_installations i JOIN oauth_apps a ON a.id = i.app_id
                     WHERE i.store_id = :store AND i.uninstalled_at IS NULL ORDER BY i.installed_at
                    """)
                    .param("store", storeId.value())
                    .query((rs, n) -> new Installation(rs.getObject("id", UUID.class), rs.getObject("app_id", UUID.class), rs.getString("name"),
                            Arrays.asList((String[]) rs.getArray("scopes").getArray()), rs.getObject("installed_by", UUID.class),
                            rs.getTimestamp("installed_at").toInstant(), rs.getObject("credential_id", UUID.class)))
                    .list();
        });
    }

    public void uninstall(UUID userId, StoreId storeId, UUID installationId) {
        tenant.runInStore(storeId, () -> {
            access.require(userId, storeId, Permission.APPS_MANAGE);
            UUID credentialId = jdbc.sql("""
                    SELECT credential_id FROM app_installations WHERE store_id = :store AND id = :id AND uninstalled_at IS NULL FOR UPDATE
                    """)
                    .param("store", storeId.value()).param("id", installationId).query(UUID.class).optional()
                    .orElseThrow(InstallationNotFound::new);
            end(storeId, installationId, credentialId);
            audit.recordUserAction(storeId, userId, "app.uninstalled", "app_installation", installationId.toString(), Map.of());
        });
    }

    /** Uninstall/replace: token revoked + credential-owned data removed (listeners), in this transaction. */
    private void end(StoreId storeId, UUID installationId, UUID credentialId) {
        Instant now = clock.instant();
        jdbc.sql("UPDATE app_installations SET uninstalled_at = :now WHERE store_id = :store AND id = :id")
                .param("now", Timestamp.from(now)).param("store", storeId.value()).param("id", installationId).update();
        if (credentials.revoke(storeId, credentialId, now)) {
            events.publishEvent(new ApiCredentialRevoked(storeId, credentialId));
        }
    }

    static Set<Scope> scopes(Set<String> codes) {
        return codes.stream().map(code -> Scope.fromCode(code).orElseThrow()).collect(Collectors.toSet());
    }

    public static class InstallationNotFound extends StoreForgeException {
        public InstallationNotFound() {
            super(HttpStatus.NOT_FOUND, "app_installation_not_found", "App installation not found.");
        }
    }
}
