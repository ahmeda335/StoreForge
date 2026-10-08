package dev.storeforge.developer.internal.credentials;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.developer.ApiCredentialRevoked;
import dev.storeforge.developer.Scope;
import dev.storeforge.developer.internal.DeveloperExceptions;
import dev.storeforge.developer.internal.DeveloperProperties;
import dev.storeforge.shared.crypto.SecretTokens;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Merchant-created API keys ("private apps") for their own integrations. Requires {@code apps:manage}. */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    static final String KIND = "API_KEY";

    private final ApiCredentialStore credentials;
    private final StoreAccess access;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final DeveloperProperties properties;
    private final Clock clock;

    /** The token is returned exactly once. Only its SHA-256 is stored. */
    public record Created(ApiCredentialStore.Credential credential, String token) {
    }

    public record UsageHour(Instant hour, int requests, int clientErrors, int serverErrors, int rateLimited) {
    }

    public Created create(UUID userId, StoreId storeId, String name, List<String> scopeCodes) {
        Set<Scope> scopes = parse(scopeCodes);
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.APPS_MANAGE);
            // Anti-escalation: the key can't do anything its creator can't do.
            access.require(userId, storeId, Scope.permissionsOf(scopes).toArray(Permission[]::new));
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('api-keys:' || :store, 0))").param("store", storeId.toString())
                    .query(Object.class).single();
            if (credentials.list(storeId, KIND).stream().filter(c -> c.revokedAt() == null).count() >= properties.maxApiKeysPerStore()) {
                throw new DeveloperExceptions.ApiKeyLimit(properties.maxApiKeysPerStore());
            }
            UUID id = Ids.newId();
            String token = ApiTokens.newToken(ApiTokens.API_KEY_PREFIX);
            credentials.insert(id, storeId, KIND, name.trim(), ApiTokens.displayPrefix(token), SecretTokens.hash(token), scopes, userId,
                    clock.instant());
            audit.recordUserAction(storeId, userId, "api_key.created", "api_key", id.toString(),
                    Map.of("name", name.trim(), "scopes", scopes.stream().map(Scope::code).sorted().toList()));
            return new Created(credentials.find(storeId, id).orElseThrow(), token);
        });
    }

    public List<ApiCredentialStore.Credential> list(UUID userId, StoreId storeId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.APPS_MANAGE);
            return credentials.list(storeId, KIND);
        });
    }

    /** Effective on the next request. Credential-owned data goes in the same transaction (event listeners). */
    public void revoke(UUID userId, StoreId storeId, UUID id) {
        tenant.runInStore(storeId, () -> {
            access.require(userId, storeId, Permission.APPS_MANAGE);
            credentials.find(storeId, id).filter(c -> KIND.equals(c.kind())).orElseThrow(DeveloperExceptions.ApiKeyNotFound::new);
            if (credentials.revoke(storeId, id, clock.instant())) {
                events.publishEvent(new ApiCredentialRevoked(storeId, id));
                audit.recordUserAction(storeId, userId, "api_key.revoked", "api_key", id.toString(), Map.of());
            }
        });
    }

    public List<UsageHour> usage(UUID userId, StoreId storeId, UUID id, int hours) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.APPS_MANAGE);
            credentials.find(storeId, id).orElseThrow(DeveloperExceptions.ApiKeyNotFound::new);
            return jdbc.sql("""
                    SELECT hour, requests, client_errors, server_errors, rate_limited FROM app_usage_hourly
                     WHERE store_id = :store AND credential_id = :id AND hour >= :since ORDER BY hour
                    """)
                    .param("store", storeId.value()).param("id", id)
                    .param("since", Timestamp.from(clock.instant().minus(Duration.ofHours(hours))))
                    .query((rs, n) -> new UsageHour(rs.getTimestamp("hour").toInstant(), rs.getInt("requests"), rs.getInt("client_errors"),
                            rs.getInt("server_errors"), rs.getInt("rate_limited")))
                    .list();
        });
    }

    private static Set<Scope> parse(List<String> codes) {
        Set<Scope> scopes = EnumSet.noneOf(Scope.class);
        for (String code : codes) {
            scopes.add(Scope.fromCode(code).orElseThrow(() -> new DeveloperExceptions.UnknownScope(code)));
        }
        return scopes;
    }
}
