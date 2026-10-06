package dev.storeforge.audit.internal;

import dev.storeforge.audit.AuditEntry;
import dev.storeforge.audit.AuditEntry.ActorType;
import dev.storeforge.audit.AuditLog;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.web.RequestIds;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
class JdbcAuditLog implements AuditLog {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    JdbcAuditLog(JdbcClient jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** MANDATORY: auditing outside the business transaction would let the two diverge. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUserAction(StoreId storeId, UUID userId, String action, String resourceType,
                                 @Nullable String resourceId, Map<String, Object> details) {
        jdbc.sql("""
                INSERT INTO audit_log (id, store_id, actor_type, actor_id, action, resource_type, resource_id,
                                       details, request_id, occurred_at)
                VALUES (:id, :storeId, :actorType, :actorId, :action, :resourceType, :resourceId,
                        CAST(:details AS jsonb), :requestId, :occurredAt)
                """)
                .param("id", Ids.newId())
                .param("storeId", storeId.value())
                .param("actorType", ActorType.USER.name())
                .param("actorId", userId)
                .param("action", action)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("details", jsonMapper.writeValueAsString(details))
                .param("requestId", MDC.get(RequestIds.MDC_KEY))
                .param("occurredAt", Timestamp.from(clock.instant()))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Page<AuditEntry> list(StoreId storeId, int limit, @Nullable Cursor after) {
        String keyset = after == null ? "" : "AND (occurred_at, id) < (:afterTime, :afterId)";
        var statement = jdbc.sql("""
                SELECT id, store_id, actor_type, actor_id, action, resource_type, resource_id, details::text AS details,
                       request_id, occurred_at
                  FROM audit_log
                 WHERE store_id = :storeId %s
                 ORDER BY occurred_at DESC, id DESC
                 LIMIT :limitPlusOne
                """.formatted(keyset))
                .param("storeId", storeId.value())
                .param("limitPlusOne", limit + 1);
        if (after != null) {
            statement = statement.param("afterTime", Timestamp.from(after.timestamp())).param("afterId", after.id());
        }
        List<AuditEntry> rows = statement.query(this::map).list();
        return Page.of(rows, limit, entry -> new Cursor(entry.occurredAt(), entry.id()));
    }

    private AuditEntry map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEntry(
                rs.getObject("id", UUID.class),
                StoreId.of(rs.getObject("store_id", UUID.class)),
                ActorType.valueOf(rs.getString("actor_type")),
                rs.getObject("actor_id", UUID.class),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                jsonMapper.readValue(rs.getString("details"), MAP),
                rs.getString("request_id"),
                rs.getTimestamp("occurred_at").toInstant());
    }
}
