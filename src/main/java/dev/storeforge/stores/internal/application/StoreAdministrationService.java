package dev.storeforge.stores.internal.application;

import dev.storeforge.audit.AuditEntry;
import dev.storeforge.audit.AuditLog;
import dev.storeforge.audit.PlatformAuditLog;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.StoreAdministration;
import dev.storeforge.stores.StoreNotFoundException;
import dev.storeforge.stores.internal.domain.Store;
import dev.storeforge.stores.internal.persistence.StoreRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class StoreAdministrationService implements StoreAdministration {

    private final StoreRepository stores;
    private final JdbcClient jdbc;
    private final TenantTransactions tenant;
    private final AuditLog audit;
    private final PlatformAuditLog platformAudit;
    private final CachingStoreDirectory directory;
    private final Clock clock;

    @Override
    public List<StoreSummary> list(@Nullable String query, @Nullable String status, int limit) {
        // stores is a directory table (no RLS): readable without a tenant context.
        return jdbc.sql("""
                        SELECT id, handle, name, status, currency, created_at FROM stores
                         WHERE (CAST(:q AS text) IS NULL OR handle ILIKE '%' || CAST(:q AS text) || '%' OR name ILIKE '%' || CAST(:q AS text) || '%')
                           AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                         ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("q", query == null || query.isBlank() ? null : query.replace("\\", "").replace("%", "").replace("_", ""))
                .param("status", status).param("limit", Math.max(1, Math.min(limit, 100)))
                .query((rs, n) -> new StoreSummary(StoreId.of(rs.getObject("id", UUID.class)), rs.getString("handle"), rs.getString("name"),
                        rs.getString("status"), rs.getString("currency").trim(), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public StoreSummary suspend(StoreId storeId, UUID adminUserId, String reason, @Nullable String clientIp) {
        return change(storeId, adminUserId, reason, clientIp, "store.suspended", store -> store.suspend(clock.instant()));
    }

    @Override
    public StoreSummary reinstate(StoreId storeId, UUID adminUserId, String reason, @Nullable String clientIp) {
        return change(storeId, adminUserId, reason, clientIp, "store.reinstated", store -> store.reinstate(clock.instant()));
    }

    private StoreSummary change(StoreId storeId, UUID adminUserId, String reason, @Nullable String clientIp, String action,
                                java.util.function.Consumer<Store> transition) {
        StoreSummary summary = tenant.inStore(storeId, () -> {
            Store store = stores.findById(storeId.value()).orElseThrow(StoreNotFoundException::new);
            transition.accept(store);
            stores.saveAndFlush(store);
            audit.record(storeId, AuditEntry.ActorType.ADMIN, adminUserId, action, "store", storeId.toString(), Map.of("reason", reason));
            platformAudit.record(adminUserId, action, "store", storeId.toString(), Map.of("reason", reason, "handle", store.handle()), clientIp);
            return new StoreSummary(storeId, store.handle(), store.name(), store.status().name(), store.currency(), store.createdAt());
        });
        directory.evictAll();
        return summary;
    }
}
