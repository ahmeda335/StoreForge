package dev.storeforge.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.jobs.internal.JobWorker;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.Json;
import dev.storeforge.support.TestJobHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The job queue's guarantees: retries with backoff, dead-lettering, crash recovery, no double-claiming. */
@ExtendWith(OutputCaptureExtension.class)
class JobQueueIT extends IntegrationTest {

    @Autowired
    Jobs jobs;

    @Autowired
    JobWorker worker;

    @Autowired
    TestJobHandlers.Recording handler;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    @Test
    void transientFailuresAreRetriedWithBackoffUntilTheySucceed(CapturedOutput output) {
        UUID jobId = enqueue(Map.of("failTimes", 2));
        Instant now = Instant.now();

        worker.runOnce(now);
        assertThat(status(jobId)).isEqualTo("READY");
        // Regression: this WARN line was once silently dropped by the JSON encoder (duplicate MDC key).
        assertThat(output.getOut()).contains("Job failed; will retry").contains("\"jobId\":\"" + jobId + "\"");
        assertThat(runAt(jobId)).as("retry scheduled in the future").isAfter(now);
        worker.runOnce(now);
        assertThat(handler.executions(jobId)).as("not due yet: no second attempt").isEqualTo(1);

        worker.runOnce(now.plus(Duration.ofHours(2)));
        worker.runOnce(now.plus(Duration.ofHours(4)));

        assertThat(status(jobId)).isEqualTo("DONE");
        assertThat(handler.executions(jobId)).isEqualTo(3);
        assertThat(lastError(jobId)).isNull();
    }

    @Test
    void exhaustedJobsGoToDeadAndCanBeRequeuedByAnOperator(CapturedOutput output) {
        UUID jobId = enqueue(Map.of("failTimes", 1000));
        Instant now = Instant.now();
        for (int day = 0; day < 10; day++) {
            worker.runOnce(now.plus(Duration.ofDays(day + 1)));
        }

        assertThat(status(jobId)).isEqualTo("DEAD");
        assertThat(handler.executions(jobId)).isEqualTo(10);
        assertThat(lastError(jobId)).contains("transient failure #10");
        assertThat(output.getOut()).contains("Job exhausted its retries; moved to DEAD");

        var overview = Json.parse(management.get("/actuator/jobs").body());
        assertThat(overview.get("deadJobs").valueStream().map(job -> job.get("id").asString())).contains(jobId.toString());
        var requeue = management.post("/actuator/jobs/" + jobId, "{}");
        assertThat(Json.parse(requeue.body()).get("requeued").asBoolean()).isTrue();
        assertThat(status(jobId)).isEqualTo("READY");
    }

    @Test
    void permanentFailuresSkipTheRetries() {
        UUID jobId = enqueue(Map.of("permanent", true, "label", "unparseable"));

        worker.runOnce(Instant.now());

        assertThat(status(jobId)).isEqualTo("DEAD");
        assertThat(handler.executions(jobId)).isEqualTo(1);
        assertThat(lastError(jobId)).contains("PermanentJobFailure").contains("unparseable");
    }

    /** A worker died holding the job: once its lease expires, another worker takes the job over. */
    @Test
    void jobsOfACrashedWorkerAreReclaimedAfterTheLease() {
        UUID jobId = enqueue(Map.of());
        Instant now = Instant.now();
        jdbc.sql("UPDATE jobs SET status = 'RUNNING', attempts = 1, locked_by = 'crashed-worker', locked_until = :until WHERE id = :id")
                .param("until", java.sql.Timestamp.from(now.plusSeconds(60))).param("id", jobId).update();

        worker.runOnce(now);
        assertThat(status(jobId)).as("lease still valid: not stolen").isEqualTo("RUNNING");

        worker.runOnce(now.plusSeconds(61));
        assertThat(status(jobId)).isEqualTo("DONE");
        assertThat(attempts(jobId)).isEqualTo(2);
    }

    @Test
    void concurrentWorkersNeverRunTheSameJobTwice() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ids.add(enqueue(Map.of()));
        }
        Instant now = Instant.now();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int w = 0; w < 4; w++) {
                workers.add(executor.submit(() -> {
                    start.await();
                    while (worker.runOnce(now) > 0) {
                        // keep claiming until the queue is empty
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : workers) {
                f.get();
            }
        }
        assertThat(ids).allSatisfy(id -> {
            assertThat(handler.executions(id)).isEqualTo(1);
            assertThat(status(id)).isEqualTo("DONE");
        });
    }

    private UUID enqueue(Map<String, Object> payload) {
        String dedupKey = UUID.randomUUID().toString();
        transaction.executeWithoutResult(status -> jobs.enqueue(null, TestJobHandlers.Recording.TYPE, dedupKey, payload));
        return jdbc.sql("SELECT id FROM jobs WHERE type = :type AND dedup_key = :key")
                .param("type", TestJobHandlers.Recording.TYPE).param("key", dedupKey).query(UUID.class).single();
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM jobs WHERE id = :id").param("id", id).query(String.class).single();
    }

    private Instant runAt(UUID id) {
        return jdbc.sql("SELECT run_at FROM jobs WHERE id = :id").param("id", id).query(java.sql.Timestamp.class).single().toInstant();
    }

    private int attempts(UUID id) {
        return jdbc.sql("SELECT attempts FROM jobs WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    private String lastError(UUID id) {
        String error = jdbc.sql("SELECT coalesce(last_error, '') FROM jobs WHERE id = :id").param("id", id).query(String.class).single();
        return error.isEmpty() ? null : error;
    }
}
