package dev.storeforge.jobs;

import dev.storeforge.shared.tenancy.StoreId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Enqueue a job for a {@link JobHandler} in the caller's transaction (MANDATORY). */
public interface Jobs {

    /** @param dedupKey a second enqueue with the same (type, dedupKey) is ignored */
    void enqueue(@Nullable StoreId storeId, String type, String dedupKey, Object payload);

    /**
     * Same, but the job is not due before {@code runAt}: a durable "check on this later", e.g. a
     * safety net behind an operation the caller is about to attempt synchronously.
     */
    void enqueue(@Nullable StoreId storeId, String type, String dedupKey, Object payload, Instant runAt);
}
