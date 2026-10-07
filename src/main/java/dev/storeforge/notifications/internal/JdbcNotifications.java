package dev.storeforge.notifications.internal;

import dev.storeforge.jobs.Jobs;
import dev.storeforge.notifications.EmailMessage;
import dev.storeforge.notifications.Notifications;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class JdbcNotifications implements Notifications {

    static final String SEND_JOB = "email.send";

    private final JdbcClient jdbc;
    private final Jobs jobs;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueueEmail(@Nullable StoreId storeId, EmailMessage message, String dedupKey, boolean sensitive) {
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                INSERT INTO email_messages (id, store_id, dedup_key, recipient, subject, body, sensitive, status, created_at)
                VALUES (:id, :storeId, :dedupKey, :recipient, :subject, :body, :sensitive, 'PENDING', :now)
                ON CONFLICT (dedup_key) DO NOTHING
                """)
                .param("id", id)
                .param("storeId", storeId == null ? null : storeId.value())
                .param("dedupKey", dedupKey)
                .param("recipient", message.to())
                .param("subject", message.subject())
                .param("body", message.body())
                .param("sensitive", sensitive)
                .param("now", Timestamp.from(clock.instant()))
                .update();
        if (inserted == 0) {
            return false;
        }
        jobs.enqueue(storeId, SEND_JOB, id.toString(), Map.of("messageId", id.toString()));
        return true;
    }
}
