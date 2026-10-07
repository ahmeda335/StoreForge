package dev.storeforge.jobs.internal;

import dev.storeforge.jobs.Outbox;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
class JdbcOutbox implements Outbox {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final TraceContexts traces;
    private final Clock clock;

    /** MANDATORY: an event outside the business transaction would reintroduce the dual-write problem. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(@Nullable StoreId storeId, String aggregateType, String aggregateId, String eventType, int eventVersion,
                       Object payload) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO outbox_events (id, store_id, aggregate_type, aggregate_id, event_type, event_version, payload,
                                           trace_parent, occurred_at)
                VALUES (:id, :storeId, :aggregateType, :aggregateId, :eventType, :eventVersion, CAST(:payload AS jsonb),
                        :traceParent, :now)
                """)
                .param("id", id)
                .param("storeId", storeId == null ? null : storeId.value())
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("eventVersion", eventVersion)
                .param("payload", jsonMapper.writeValueAsString(payload))
                .param("traceParent", traces.currentTraceParent())
                .param("now", Timestamp.from(clock.instant()))
                .update();
        return id;
    }
}
