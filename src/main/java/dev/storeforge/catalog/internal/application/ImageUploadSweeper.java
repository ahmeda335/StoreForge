package dev.storeforge.catalog.internal.application;

import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.storage.ObjectStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes uploads that were started but never completed (the client gave up, crashed, or uploaded
 * something we refused to look at). Same two steps as the reservation sweeper (ADR-011): which
 * stores, via a definer function; then per store under RLS, with SKIP LOCKED. Their objects (if any
 * were uploaded) are deleted by a retried job.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImageUploadSweeper {

    /** Well past the upload URL's lifetime: nobody can still be uploading. */
    static final Duration ABANDONED_AFTER = ProductImageService.UPLOAD_TTL.plus(Duration.ofMinutes(50));
    private static final int MAX_STORES_PER_RUN = 100;
    private static final int BATCH = 500;

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final TenantTransactions tenant;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${storeforge.catalog.upload-sweeper-interval:15m}", initialDelayString = "${storeforge.catalog.upload-sweeper-interval:15m}")
    public void sweep() {
        int removed = sweepOnce();
        if (removed > 0) {
            log.atInfo().addKeyValue("abandonedUploads", removed).log("Removed abandoned image uploads");
        }
    }

    public int sweepOnce() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(ABANDONED_AFTER));
        int total = 0;
        List<UUID> stores = jdbc.sql("SELECT store_id FROM stores_with_abandoned_image_uploads(:cutoff, :max)")
                .param("cutoff", cutoff).param("max", MAX_STORES_PER_RUN).query(UUID.class).list();
        for (UUID store : stores) {
            StoreId storeId = StoreId.of(store);
            total += tenant.inStore(storeId, () -> {
                List<String> keys = jdbc.sql("""
                                DELETE FROM product_images WHERE id IN (
                                    SELECT id FROM product_images WHERE store_id = :store AND status = 'PENDING' AND created_at < :cutoff
                                     LIMIT :batch FOR UPDATE SKIP LOCKED)
                                RETURNING object_key
                                """)
                        .param("store", store).param("cutoff", cutoff).param("batch", BATCH).query(String.class).list();
                keys.forEach(key -> storage.deleteLater(storeId, key));
                return keys.size();
            });
        }
        meterRegistry.counter("storeforge.catalog.image_uploads.abandoned").increment(total);
        return total;
    }

}
