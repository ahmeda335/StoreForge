package dev.storeforge.identity.internal.persistence;

import dev.storeforge.shared.ids.Ids;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Refresh tokens are stored only as SHA-256 hashes: a database leak must not yield usable tokens.
 * (A fast hash is fine here, unlike for passwords: the tokens are 256 random bits, so there is
 * nothing to brute-force.)
 *
 * <p>All tokens created by rotating one login form a FAMILY. Reuse of an already-rotated token means
 * two parties hold tokens from the same family (one of them stole it), so the whole family is
 * revoked.
 */
@Repository
public class RefreshTokenStore {

    private final JdbcClient jdbc;

    RefreshTokenStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ConsumedToken(UUID userId, UUID familyId) {
    }

    public record StoredToken(UUID userId, UUID familyId, boolean used, boolean revoked) {
    }

    public void insert(UUID userId, UUID familyId, String tokenHash, Instant expiresAt, Instant now) {
        jdbc.sql("""
                INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, expires_at, created_at)
                VALUES (:id, :userId, :familyId, :hash, :expiresAt, :now)
                """)
                .param("id", Ids.newId())
                .param("userId", userId)
                .param("familyId", familyId)
                .param("hash", tokenHash)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now))
                .update();
    }

    /**
     * Marks the token used if, and only if, it is currently valid. One conditional UPDATE: when two
     * requests present the same token concurrently, PostgreSQL row-locks it, the second request
     * re-evaluates {@code used_at IS NULL} after the first commits, and gets no row back. Exactly
     * one caller can rotate a token.
     */
    public Optional<ConsumedToken> consume(String tokenHash, Instant now) {
        return jdbc.sql("""
                UPDATE refresh_tokens SET used_at = :now
                 WHERE token_hash = :hash
                   AND used_at IS NULL AND revoked_at IS NULL AND expires_at > :now
                RETURNING user_id, family_id
                """)
                .param("hash", tokenHash)
                .param("now", Timestamp.from(now))
                .query((rs, rowNum) -> new ConsumedToken(rs.getObject("user_id", UUID.class), rs.getObject("family_id", UUID.class)))
                .optional();
    }

    public Optional<StoredToken> find(String tokenHash) {
        return jdbc.sql("SELECT user_id, family_id, used_at, revoked_at FROM refresh_tokens WHERE token_hash = :hash")
                .param("hash", tokenHash)
                .query((rs, rowNum) -> new StoredToken(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class),
                        rs.getTimestamp("used_at") != null,
                        rs.getTimestamp("revoked_at") != null))
                .optional();
    }

    public int revokeFamily(UUID familyId, Instant now) {
        return jdbc.sql("UPDATE refresh_tokens SET revoked_at = :now WHERE family_id = :familyId AND revoked_at IS NULL")
                .param("familyId", familyId)
                .param("now", Timestamp.from(now))
                .update();
    }

    public int revokeAllForUser(UUID userId, Instant now) {
        return jdbc.sql("UPDATE refresh_tokens SET revoked_at = :now WHERE user_id = :userId AND revoked_at IS NULL")
                .param("userId", userId)
                .param("now", Timestamp.from(now))
                .update();
    }
}
