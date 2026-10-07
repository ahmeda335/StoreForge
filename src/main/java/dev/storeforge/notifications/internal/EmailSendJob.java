package dev.storeforge.notifications.internal;

import dev.storeforge.jobs.JobHandler;
import dev.storeforge.jobs.PermanentJobFailure;
import dev.storeforge.notifications.EmailMessage;
import dev.storeforge.notifications.EmailSender;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Sends one queued email. SMTP failures throw, so the job retries with backoff instead of the email
 * being lost (the P2 limitation, EXP-002). Already-SENT messages are skipped (redelivered job).
 *
 * <p>The send happens outside any database transaction; marking SENT is a separate statement. A
 * crash between the two re-sends on retry: email is at-least-once, which is acceptable here. Holding
 * a transaction open around a network call is what we must never do.
 */
@Component
@RequiredArgsConstructor
class EmailSendJob implements JobHandler {

    private final JdbcClient jdbc;
    private final EmailSender emailSender;
    private final Clock clock;

    record Stored(String recipient, String subject, @Nullable String body, String status, boolean sensitive) {
    }

    @Override
    public String type() {
        return JdbcNotifications.SEND_JOB;
    }

    @Override
    public void handle(Job job) {
        UUID messageId = UUID.fromString(job.payload().get("messageId").asString());
        Stored message = jdbc.sql("SELECT recipient, subject, body, status, sensitive FROM email_messages WHERE id = :id")
                .param("id", messageId)
                .query((rs, rowNum) -> new Stored(rs.getString("recipient"), rs.getString("subject"), rs.getString("body"),
                        rs.getString("status"), rs.getBoolean("sensitive")))
                .optional()
                .orElseThrow(() -> new PermanentJobFailure("Email message " + messageId + " not found"));
        if ("SENT".equals(message.status())) {
            return;
        }
        emailSender.send(new EmailMessage(message.recipient(), message.subject(), message.body()));
        // Sent: secrets (single-use links) have no reason to stay in the database any longer.
        jdbc.sql("""
                UPDATE email_messages SET status = 'SENT', sent_at = :now,
                       body = CASE WHEN sensitive THEN NULL ELSE body END
                 WHERE id = :id AND status = 'PENDING'
                """)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", messageId)
                .update();
    }
}
