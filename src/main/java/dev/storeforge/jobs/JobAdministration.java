package dev.storeforge.jobs;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Operator view of the job queue (platform API, P14; also exposed on the private management port). */
public interface JobAdministration {

    record DeadJob(UUID id, String type, @Nullable UUID storeId, int attempts, @Nullable String lastError, Instant updatedAt) {
    }

    record Overview(Map<String, Long> jobsByStatus, long unpublishedEvents, double oldestReadyAgeSeconds, List<DeadJob> deadJobs) {
    }

    Overview overview(int deadJobLimit);

    /** A DEAD job gets a fresh set of attempts. Joins the caller's transaction. @return false if it wasn't DEAD */
    boolean requeue(UUID jobId);
}
