package dev.storeforge.jobs.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Queues and logs grow forever unless something deletes old rows. Deletes, in order: finished jobs
 * past retention; published events whose jobs are all finished; sent emails after 30 days;
 * idempotency keys after 24h (tenant data: via a delete-only definer function, ADR-016).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class Housekeeping {

    static final Duration IDEMPOTENCY_KEY_RETENTION = Duration.ofHours(24);
    static final Duration SENT_EMAIL_RETENTION = Duration.ofDays(30);

    private final JobStore jobs;
    private final JdbcClient jdbc;
    private final JobsProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${storeforge.jobs.housekeeping-interval:1h}", initialDelayString = "${storeforge.jobs.housekeeping-interval:1h}")
    void scheduled() {
        run(clock.instant());
    }

    public Result run(Instant now) {
        Instant cutoff = now.minus(properties.retention());
        int doneJobs = jobs.deleteDoneBefore(cutoff);
        int events = jdbc.sql("""
                DELETE FROM outbox_events e
                 WHERE e.published_at < :cutoff
                   AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.event_id = e.id AND j.status <> 'DONE')
                """)
                .param("cutoff", Timestamp.from(cutoff))
                .update();
        int emails = jdbc.sql("DELETE FROM email_messages WHERE status = 'SENT' AND sent_at < :cutoff")
                .param("cutoff", Timestamp.from(now.minus(SENT_EMAIL_RETENTION)))
                .update();
        int keys = jdbc.sql("SELECT purge_idempotency_keys(:cutoff)")
                .param("cutoff", Timestamp.from(now.minus(IDEMPOTENCY_KEY_RETENTION)))
                .query(Integer.class)
                .single();
        Result result = new Result(doneJobs, events, emails, keys);
        if (doneJobs + events + emails + keys > 0) {
            log.atInfo().addKeyValue("doneJobs", doneJobs).addKeyValue("events", events).addKeyValue("emails", emails)
                    .addKeyValue("idempotencyKeys", keys).log("Housekeeping removed expired rows");
        }
        return result;
    }

    public record Result(int doneJobs, int events, int emails, int idempotencyKeys) {
    }
}
