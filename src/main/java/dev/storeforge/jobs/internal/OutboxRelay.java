package dev.storeforge.jobs.internal;

import dev.storeforge.jobs.EventHandler;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Moves committed outbox events into the job queue: one job per (subscribed handler, event).
 *
 * <p>Because outbox and jobs live in the same database, this step is itself ONE transaction: claim
 * unpublished events (SKIP LOCKED, so several app instances can relay in parallel), insert the jobs,
 * mark the events published, commit. It can't half-happen. When a message broker replaces the job
 * table (P18), this is the place that becomes "publish to the broker, then mark published", and that
 * version CAN fail in between, which is why consumers are idempotent from day one.
 */
@Component
@RequiredArgsConstructor
class OutboxRelay {

    private final JdbcClient jdbc;
    private final JobStore jobs;
    private final List<EventHandler> handlers;
    private final JobsProperties properties;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    record Unpublished(UUID id, @Nullable UUID storeId, String eventType, @Nullable String traceParent) {
    }

    /** @return number of events published in this run */
    int publishOnce() {
        Integer published = transaction.execute(status -> {
            Instant now = clock.instant();
            List<Unpublished> events = jdbc.sql("""
                    SELECT id, store_id, event_type, trace_parent FROM outbox_events
                     WHERE published_at IS NULL
                     ORDER BY occurred_at, id
                     LIMIT :batch
                       FOR UPDATE SKIP LOCKED
                    """)
                    .param("batch", properties.batchSize() * 4)
                    .query((rs, rowNum) -> new Unpublished(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                            rs.getString("event_type"), rs.getString("trace_parent")))
                    .list();
            for (Unpublished event : events) {
                for (EventHandler handler : handlers) {
                    if (handler.eventTypes().contains(event.eventType())) {
                        // dedup (type, event id): even a re-run of this loop can't create a second job
                        jobs.insert(event.storeId(), JobWorker.EVENT_PREFIX + handler.name(), event.id().toString(), event.id(),
                                jsonMapper.writeValueAsString(Map.of("eventId", event.id().toString())),
                                properties.maxAttempts(), event.traceParent(), now);
                    }
                }
                jdbc.sql("UPDATE outbox_events SET published_at = :now WHERE id = :id")
                        .param("now", Timestamp.from(now))
                        .param("id", event.id())
                        .update();
            }
            return events.size();
        });
        return published == null ? 0 : published;
    }

    long unpublishedCount() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single();
    }
}
