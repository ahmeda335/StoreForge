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
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every storefront request starts with "which store is this hostname?". The answer changes rarely
 * and is tiny, so it is cached in-process (Caffeine) for 60 seconds.
 *
 * <p>Trade-off, accepted and documented: suspending a store or changing a domain takes up to 60s
 * to reach every instance. Negative results ("no such host") are cached too, otherwise random
 * hostnames would bypass the cache and hit the database on every request.
 *
 * <p>The database is queried OUTSIDE the cache: never {@code cache.get(key, loader)} with a blocking
 * loader. Caffeine runs loaders inside {@code ConcurrentHashMap.compute}, a {@code synchronized}
 * section; on JDK 21 a virtual thread blocking there pins its carrier, and so does every request
 * waiting for the same key. Under load all carriers were pinned while the loader waited for a pooled
 * connection held by parked threads: a permanent deadlock (EXP-023). A miss may now load twice;
 * the second result just overwrites the first.
 */
@Component
@RequiredArgsConstructor
class CachingStoreDirectory implements StoreDirectory {

    private final JdbcClient jdbc;
    private final Cache<UUID, Optional<StoreInfo>> byId = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(60)).build();
    private final Cache<String, Optional<StoreInfo>> byHost = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(60)).build();

    /** After a status change. Other instances catch up within the cache TTL (60 s). */
    void evictAll() {
        byId.invalidateAll();
        byHost.invalidateAll();
    }

    @Override
    public Optional<StoreInfo> find(StoreId storeId) {
        return cached(byId, storeId.value(), id -> query("WHERE s.id = :key", id));
    }

    @Override
    public Optional<StoreInfo> resolveHost(String hostname) {
        return cached(byHost, hostname.toLowerCase(Locale.ROOT), host -> query(
                "JOIN store_domains d ON d.store_id = s.id WHERE d.hostname = CAST(:key AS citext) AND d.verified_at IS NOT NULL", host));
    }

    private static <K> Optional<StoreInfo> cached(Cache<K, Optional<StoreInfo>> cache, K key, Function<K, Optional<StoreInfo>> load) {
        Optional<StoreInfo> hit = cache.getIfPresent(key);
        if (hit != null) {
            return hit;
        }
        Optional<StoreInfo> loaded = load.apply(key);   // no lock held while waiting for the database
        cache.put(key, loaded);
        return loaded;
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
