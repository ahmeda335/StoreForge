package dev.storeforge.jobs.internal;

import dev.storeforge.shared.ids.Ids;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All job-queue SQL. Read the WHERE clauses: they ARE the queue's correctness. */
@Repository
@RequiredArgsConstructor
class JobStore {

    private final JdbcClient jdbc;

    record ClaimedJob(UUID id, @Nullable UUID storeId, String type, String payload, int attempts, int maxAttempts,
                      @Nullable String traceParent, @Nullable UUID eventId) {
    }

    record DeadJob(UUID id, String type, @Nullable UUID storeId, int attempts, @Nullable String lastError, Instant updatedAt) {
    }

    /** @return true if inserted, false if a job with the same (type, dedupKey) already exists */
    boolean insert(@Nullable UUID storeId, String type, String dedupKey, @Nullable UUID eventId, String payloadJson,
                   int maxAttempts, @Nullable String traceParent, Instant runAt, Instant now) {
        return jdbc.sql("""
                INSERT INTO jobs (id, store_id, type, dedup_key, event_id, payload, status, max_attempts, run_at,
                                  trace_parent, created_at, updated_at)
                VALUES (:id, :storeId, :type, :dedupKey, :eventId, CAST(:payload AS jsonb), 'READY', :maxAttempts, :runAt,
                        :traceParent, :now, :now)
                ON CONFLICT (type, dedup_key) DO NOTHING
                """)
                .param("id", Ids.newId())
                .param("storeId", storeId)
                .param("type", type)
                .param("dedupKey", dedupKey)
                .param("eventId", eventId)
                .param("payload", payloadJson)
                .param("maxAttempts", maxAttempts)
                .param("traceParent", traceParent)
                .param("runAt", Timestamp.from(runAt))
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    /**
     * Claims up to {@code batch} jobs: due READY jobs, plus RUNNING jobs whose lease expired (their
     * worker crashed or hung). FOR UPDATE SKIP LOCKED lets N workers claim concurrently without
     * blocking each other or taking the same job. The claim COMMITS before the work starts, so a
     * slow handler never holds row locks or a database connection.
     */
    List<ClaimedJob> claim(String workerId, Instant now, Instant leaseUntil, int batch, Collection<String> excludedTypes) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'RUNNING', attempts = attempts + 1, locked_by = :worker,
                                locked_until = :leaseUntil, updated_at = :now
                 WHERE id IN (SELECT id FROM jobs
                               WHERE ((status = 'READY' AND run_at <= :now)
                                  OR (status = 'RUNNING' AND locked_until < :now))
                                 AND type <> ALL (CAST(:excluded AS text[]))
                               ORDER BY run_at
                               LIMIT :batch
                                 FOR UPDATE SKIP LOCKED)
                RETURNING id, store_id, type, payload::text AS payload, attempts, max_attempts, trace_parent, event_id
                """)
                .param("worker", workerId)
                .param("leaseUntil", Timestamp.from(leaseUntil))
                .param("now", Timestamp.from(now))
                .param("batch", batch)
                .param("excluded", excludedTypes.toArray(String[]::new))
                .query((rs, rowNum) -> new ClaimedJob(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                        rs.getString("type"), rs.getString("payload"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                        rs.getString("trace_parent"), rs.getObject("event_id", UUID.class)))
                .list();
    }

    /** Gives back a job claimed beyond a concurrency limit, as if it had never been claimed. */
    void release(UUID jobId, String workerId, Instant now) {
        jdbc.sql("""
                UPDATE jobs SET status = 'READY', attempts = attempts - 1, locked_by = NULL, locked_until = NULL, updated_at = :now
                 WHERE id = :id AND locked_by = :worker AND status = 'RUNNING'
                """)
                .param("id", jobId)
                .param("worker", workerId)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Only the worker that still owns the lease may finish the job (a reclaimed job belongs to its new worker). */
    boolean markDone(UUID jobId, String workerId, Instant now) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'DONE', completed_at = :now, updated_at = :now, locked_by = NULL,
                                locked_until = NULL, last_error = NULL
                 WHERE id = :id AND locked_by = :worker AND status = 'RUNNING'
                """)
                .param("id", jobId)
                .param("worker", workerId)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    boolean markRetry(UUID jobId, String workerId, Instant runAt, String error, Instant now) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'READY', run_at = :runAt, last_error = :error, locked_by = NULL,
                                locked_until = NULL, updated_at = :now
                 WHERE id = :id AND locked_by = :worker AND status = 'RUNNING'
                """)
                .param("id", jobId)
                .param("worker", workerId)
                .param("runAt", Timestamp.from(runAt))
                .param("error", error)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    boolean markDead(UUID jobId, String workerId, String error, Instant now) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'DEAD', last_error = :error, locked_by = NULL, locked_until = NULL, updated_at = :now
                 WHERE id = :id AND locked_by = :worker AND status = 'RUNNING'
                """)
                .param("id", jobId)
                .param("worker", workerId)
                .param("error", error)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    /** Operator action: give a DEAD job a fresh set of attempts. */
    boolean requeue(UUID jobId, Instant now) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'READY', attempts = 0, run_at = :now, updated_at = :now
                 WHERE id = :id AND status = 'DEAD'
                """)
                .param("id", jobId)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    Map<String, Long> countsByStatus() {
        Map<String, Long> counts = new java.util.HashMap<>(Map.of("READY", 0L, "RUNNING", 0L, "DONE", 0L, "DEAD", 0L));
        jdbc.sql("SELECT status, count(*) AS n FROM jobs GROUP BY status")
                .query(rs -> {
                    counts.put(rs.getString("status"), rs.getLong("n"));
                });
        return counts;
    }

    /** Seconds the oldest due READY job has been waiting: "is work piling up?" (better alert signal than depth). */
    double oldestReadyAgeSeconds(Instant now) {
        Timestamp oldest = jdbc.sql("SELECT min(run_at) FROM jobs WHERE status = 'READY' AND run_at <= :now")
                .param("now", Timestamp.from(now))
                .query(Timestamp.class)
                .optional()
                .orElse(null);
        return oldest == null ? 0 : Math.max(0, (now.toEpochMilli() - oldest.getTime()) / 1000.0);
    }

    List<DeadJob> deadJobs(int limit) {
        return jdbc.sql("""
                SELECT id, type, store_id, attempts, last_error, updated_at FROM jobs
                 WHERE status = 'DEAD' ORDER BY updated_at DESC LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, rowNum) -> new DeadJob(rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getObject("store_id", UUID.class), rs.getInt("attempts"), rs.getString("last_error"),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    int deleteDoneBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM jobs WHERE status = 'DONE' AND completed_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }
}
