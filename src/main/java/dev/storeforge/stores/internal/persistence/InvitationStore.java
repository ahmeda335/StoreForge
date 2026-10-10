package dev.storeforge.stores.internal.persistence;

import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Staff invitations (tenant data: RLS). Tokens are stored hashed, like all bearer secrets. */
@Repository
@RequiredArgsConstructor
public class InvitationStore {

    private final JdbcClient jdbc;

    public record PendingInvitation(UUID id, String email, UUID roleId, UUID invitedBy, Instant expiresAt, Instant createdAt) {
    }

    public record AcceptedInvitation(UUID id, String email, UUID roleId) {
    }

    public UUID insert(StoreId storeId, String email, UUID roleId, String tokenHash, UUID invitedBy, Instant expiresAt, Instant now) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO invitations (id, store_id, email, role_id, token_hash, invited_by, expires_at, created_at)
                VALUES (:id, :storeId, :email, :roleId, :tokenHash, :invitedBy, :expiresAt, :now)
                """)
                .param("id", id)
                .param("storeId", storeId.value())
                .param("email", email)
                .param("roleId", roleId)
                .param("tokenHash", tokenHash)
                .param("invitedBy", invitedBy)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now))
                .update();
        return id;
    }

    /** Re-inviting replaces the previous pending invitation for that email. */
    public void revokePendingFor(StoreId storeId, String email, Instant now) {
        jdbc.sql("""
                UPDATE invitations SET revoked_at = :now
                 WHERE store_id = :storeId AND email = CAST(:email AS citext) AND accepted_at IS NULL AND revoked_at IS NULL
                """)
                .param("storeId", storeId.value())
                .param("email", email)
                .param("now", Timestamp.from(now))
                .update();
    }

    public int revoke(StoreId storeId, UUID invitationId, Instant now) {
        return jdbc.sql("""
                UPDATE invitations SET revoked_at = :now
                 WHERE store_id = :storeId AND id = :id AND accepted_at IS NULL AND revoked_at IS NULL
                """)
                .param("storeId", storeId.value())
                .param("id", invitationId)
                .param("now", Timestamp.from(now))
                .update();
    }

    public List<PendingInvitation> listPending(StoreId storeId, Instant now) {
        return jdbc.sql("""
                SELECT id, email, role_id, invited_by, expires_at, created_at FROM invitations
                 WHERE store_id = :storeId AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > :now
                 ORDER BY created_at DESC
                """)
                .param("storeId", storeId.value())
                .param("now", Timestamp.from(now))
                .query((rs, rowNum) -> new PendingInvitation(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getObject("role_id", UUID.class), rs.getObject("invited_by", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** Atomically consumes a valid invitation (same single-use pattern as email tokens). */
    public Optional<AcceptedInvitation> accept(StoreId storeId, String tokenHash, UUID userId, Instant now) {
        return jdbc.sql("""
                UPDATE invitations SET accepted_at = :now, accepted_by = :userId
                 WHERE store_id = :storeId AND token_hash = :tokenHash
                   AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > :now
                RETURNING id, email, role_id
                """)
                .param("storeId", storeId.value())
                .param("tokenHash", tokenHash)
                .param("userId", userId)
                .param("now", Timestamp.from(now))
                .query((rs, rowNum) -> new AcceptedInvitation(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getObject("role_id", UUID.class)))
                .optional();
    }
}
