package dev.storeforge.stores.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.StoreInfo;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * EXP-023 regression: the database lookup must run with NO lock held. Loading inside the cache's
 * compute (a synchronized section) pinned virtual threads and deadlocked the app under load.
 */
class CachingStoreDirectoryTest {

    @Test
    @SuppressWarnings("unchecked")   // RowMapper matcher on a generic method
    void aSlowLookupDoesNotBlockOtherRequestsForTheSameHost() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        CountDownLatch firstQueryStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstQuery = new CountDownLatch(1);
        AtomicInteger queries = new AtomicInteger();
        StoreInfo store = new StoreInfo(StoreId.of(UUID.randomUUID()), "acme", "Acme", Currency.getInstance("USD"), true);
        when(jdbc.sql(anyString()).param(anyString(), any()).query(any(RowMapper.class)).optional()).thenAnswer(invocation -> {
            if (queries.incrementAndGet() == 1) {
                firstQueryStarted.countDown();
                releaseFirstQuery.await(10, TimeUnit.SECONDS);   // the database is slow for the first request
            }
            return Optional.of(store);
        });
        CachingStoreDirectory directory = new CachingStoreDirectory(jdbc);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var slow = executor.submit(() -> directory.resolveHost("acme.storeforge.localhost"));
            assertThat(firstQueryStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> directory.resolveHost("acme.storeforge.localhost"));
            // With the loader inside compute(), this request would wait for the first one's lock.
            assertThat(second.get(2, TimeUnit.SECONDS)).contains(store);
            releaseFirstQuery.countDown();
            assertThat(slow.get(5, TimeUnit.SECONDS)).contains(store);
        }
        assertThat(directory.resolveHost("ACME.storeforge.localhost")).as("cached afterwards").contains(store);
        assertThat(queries.get()).isEqualTo(2);
    }
}
