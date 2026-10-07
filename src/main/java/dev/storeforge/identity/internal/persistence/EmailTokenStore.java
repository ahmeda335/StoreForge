package dev.storeforge.identity.internal.persistence;

import dev.storeforge.shared.ids.Ids;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Single-use, expiring tokens sent by email (verification, password reset). Stored hashed. */
@Repository
@RequiredArgsConstructor
public class EmailTokenStore {

    public enum Purpose { VERIFY_EMAIL, RESET_PASSWORD }

    private final JdbcClient jdbc;

    /** Only the newest link works: older unused tokens of the same purpose are deleted. */
    public void replace(UUID userId, Purpose purpose, String tokenHash, Instant expiresAt, Instant now) {
        jdbc.sql("DELETE FROM email_tokens WHERE user_id = :userId AND purpose = :purpose AND used_at IS NULL")
                .param("userId", userId)
                .param("purpose", purpose.name())
                .update();
        jdbc.sql("""
                INSERT INTO email_tokens (id, user_id, purpose, token_hash, expires_at, created_at)
                VALUES (:id, :userId, :purpose, :hash, :expiresAt, :now)
                """)
                .param("id", Ids.newId())
                .param("userId", userId)
                .param("purpose", purpose.name())
                .param("hash", tokenHash)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Atomically marks a valid token used; empty if unknown, expired, already used, or wrong purpose. */
    public Optional<UUID> consume(String tokenHash, Purpose purpose, Instant now) {
        return jdbc.sql("""
                UPDATE email_tokens SET used_at = :now
                 WHERE token_hash = :hash AND purpose = :purpose
                   AND used_at IS NULL AND expires_at > :now
                RETURNING user_id
                """)
                .param("hash", tokenHash)
                .param("purpose", purpose.name())
                .param("now", Timestamp.from(now))
                .query(UUID.class)
                .optional();
    }
}
