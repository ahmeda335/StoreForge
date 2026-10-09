package dev.storeforge.notifications.internal;

import dev.storeforge.shared.privacy.PersonalData;
import dev.storeforge.shared.tenancy.StoreId;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Emails the store sent the customer (order confirmations): recipient and body removed, the fact that one was sent kept. */
@Component
@RequiredArgsConstructor
class EmailPersonalData implements PersonalData {

    private final JdbcClient jdbc;

    @Override
    public String section() {
        return "emails";
    }

    @Override
    public @Nullable Object export(StoreId storeId, DataSubject subject) {
        var subjects = jdbc.sql("SELECT subject FROM email_messages WHERE store_id = :store AND recipient = CAST(:email AS citext) ORDER BY created_at")
                .param("store", storeId.value()).param("email", subject.email()).query(String.class).list();
        return subjects.isEmpty() ? null : subjects;
    }

    @Override
    public void erase(StoreId storeId, DataSubject subject) {
        // PENDING messages keep their body (the CHECK requires one until sent) but can no longer reach anyone.
        jdbc.sql("""
                UPDATE email_messages SET recipient = :replacement, body = CASE WHEN status = 'SENT' THEN NULL ELSE '(erased)' END
                 WHERE store_id = :store AND recipient = CAST(:email AS citext)
                """)
                .param("store", storeId.value()).param("email", subject.email()).param("replacement", subject.replacementEmail()).update();
    }
}
