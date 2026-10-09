package dev.storeforge.audit.internal;

import dev.storeforge.audit.PlatformAuditLog;
import dev.storeforge.shared.ids.Ids;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
class JdbcPlatformAuditLog implements PlatformAuditLog {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID adminUserId, String action, @Nullable String targetType, @Nullable String targetId, Map<String, Object> details,
                       @Nullable String clientIp) {
        jdbc.sql("""
                INSERT INTO platform_audit_log (id, admin_user_id, action, target_type, target_id, details, client_ip, occurred_at)
                VALUES (:id, :admin, :action, :targetType, :targetId, CAST(:details AS jsonb), :ip, :at)
                """)
                .param("id", Ids.newId()).param("admin", adminUserId).param("action", action).param("targetType", targetType)
                .param("targetId", targetId).param("details", jsonMapper.writeValueAsString(details)).param("ip", clientIp)
                .param("at", Timestamp.from(clock.instant()))
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Entry> recent(int limit) {
        return jdbc.sql("""
                        SELECT id, admin_user_id, action, target_type, target_id, details::text AS details, client_ip, occurred_at
                          FROM platform_audit_log ORDER BY occurred_at DESC, id DESC LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("admin_user_id", UUID.class), rs.getString("action"),
                        rs.getString("target_type"), rs.getString("target_id"), jsonMapper.readValue(rs.getString("details"), MAP),
                        rs.getString("client_ip"), rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }
}
