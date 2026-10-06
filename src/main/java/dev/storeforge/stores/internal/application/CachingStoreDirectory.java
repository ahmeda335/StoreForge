package dev.storeforge.stores.internal.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.StoreDirectory;
import dev.storeforge.stores.StoreInfo;
import java.time.Duration;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every storefront request starts with "which store is this hostname?". The answer changes rarely
 * and is tiny, so it is cached in-process (Caffeine) for 60 seconds.
 *
 * <p>Trade-off, accepted and documented: suspending a store or changing a domain takes up to 60s
 * to reach every instance. Negative results ("no such host") are cached too, otherwise random
 * hostnames would bypass the cache and hit the database on every request.
 */
@Component
class CachingStoreDirectory implements StoreDirectory {

    private final JdbcClient jdbc;
    private final Cache<UUID, Optional<StoreInfo>> byId = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(60)).build();
    private final Cache<String, Optional<StoreInfo>> byHost = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(60)).build();

    CachingStoreDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoreInfo> find(StoreId storeId) {
        return byId.get(storeId.value(), id -> query("WHERE s.id = :key", id));
    }

    @Override
    public Optional<StoreInfo> resolveHost(String hostname) {
        String normalized = hostname.toLowerCase(Locale.ROOT);
        return byHost.get(normalized, host -> query(
                "JOIN store_domains d ON d.store_id = s.id WHERE d.hostname = :key AND d.verified_at IS NOT NULL", host));
    }

    private Optional<StoreInfo> query(String condition, Object key) {
        return jdbc.sql("SELECT s.id, s.handle, s.name, s.currency, s.status FROM stores s " + condition)
                .param("key", key)
                .query((rs, rowNum) -> new StoreInfo(StoreId.of(rs.getObject("id", UUID.class)), rs.getString("handle"),
                        rs.getString("name"), Currency.getInstance(rs.getString("currency").trim()),
                        "ACTIVE".equals(rs.getString("status"))))
                .optional();
    }
}
