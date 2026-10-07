package dev.storeforge.jobs.internal;

import dev.storeforge.jobs.Inbox;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class JdbcInbox implements Inbox {

    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(StoreId storeId, String consumer, UUID messageId) {
        return jdbc.sql("""
                INSERT INTO processed_messages (store_id, consumer, message_id, processed_at)
                VALUES (:storeId, :consumer, :messageId, :now)
                ON CONFLICT DO NOTHING
                """)
                .param("storeId", storeId.value())
                .param("consumer", consumer)
                .param("messageId", messageId)
                .param("now", Timestamp.from(clock.instant()))
                .update() == 1;
    }
}
