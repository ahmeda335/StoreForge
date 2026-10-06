package dev.storeforge.stores.internal.persistence;

import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreMember;
import dev.storeforge.stores.internal.domain.Role;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Memberships (tenant directory) joined with roles (tenant data). The joins to {@code roles} only
 * return rows inside a tenant transaction for the same store: RLS hides other tenants' roles.
 */
@Repository
public class MemberStore {

    private final JdbcClient jdbc;

    MemberStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record MemberView(UUID userId, UUID roleId, String roleName, boolean owner, Instant joinedAt) {
    }

    public record StoreSummary(UUID id, String handle, String name, String status) {
    }

    public void insert(StoreId storeId, UUID userId, UUID roleId, Instant now) {
        jdbc.sql("""
                INSERT INTO store_members (store_id, user_id, role_id, created_at, updated_at)
                VALUES (:storeId, :userId, :roleId, :now, :now)
                """)
                .param("storeId", storeId.value())
                .param("userId", userId)
                .param("roleId", roleId)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** The membership with its role and the store's status, or empty if the user is not a member. */
    public Optional<MembershipWithStatus> findMembership(StoreId storeId, UUID userId) {
        return jdbc.sql("""
                SELECT m.user_id, m.role_id, r.name AS role_name, r.is_owner, r.permissions, s.status
                  FROM store_members m
                  JOIN stores s ON s.id = m.store_id
                  JOIN roles r ON r.store_id = m.store_id AND r.id = m.role_id
                 WHERE m.store_id = :storeId AND m.user_id = :userId
                """)
                .param("storeId", storeId.value())
                .param("userId", userId)
                .query((rs, rowNum) -> new MembershipWithStatus(
                        new StoreMember(storeId, rs.getObject("user_id", UUID.class), rs.getObject("role_id", UUID.class),
                                rs.getString("role_name"), rs.getBoolean("is_owner"), permissions(rs)),
                        rs.getString("status")))
                .optional();
    }

    public record MembershipWithStatus(StoreMember member, String storeStatus) {
    }

    public List<MemberView> list(StoreId storeId) {
        return jdbc.sql("""
                SELECT m.user_id, m.role_id, r.name AS role_name, r.is_owner, m.created_at
                  FROM store_members m
                  JOIN roles r ON r.store_id = m.store_id AND r.id = m.role_id
                 WHERE m.store_id = :storeId
                 ORDER BY m.created_at, m.user_id
                """)
                .param("storeId", storeId.value())
                .query((rs, rowNum) -> new MemberView(rs.getObject("user_id", UUID.class), rs.getObject("role_id", UUID.class),
                        rs.getString("role_name"), rs.getBoolean("is_owner"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public boolean exists(StoreId storeId, UUID userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM store_members WHERE store_id = :storeId AND user_id = :userId)")
                .param("storeId", storeId.value())
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    public int changeRole(StoreId storeId, UUID userId, UUID roleId, Instant now) {
        return jdbc.sql("UPDATE store_members SET role_id = :roleId, updated_at = :now WHERE store_id = :storeId AND user_id = :userId")
                .param("storeId", storeId.value())
                .param("userId", userId)
                .param("roleId", roleId)
                .param("now", Timestamp.from(now))
                .update();
    }

    public int remove(StoreId storeId, UUID userId) {
        return jdbc.sql("DELETE FROM store_members WHERE store_id = :storeId AND user_id = :userId")
                .param("storeId", storeId.value())
                .param("userId", userId)
                .update();
    }

    public int countOwners(StoreId storeId) {
        return jdbc.sql("""
                SELECT count(*) FROM store_members m
                  JOIN roles r ON r.store_id = m.store_id AND r.id = m.role_id
                 WHERE m.store_id = :storeId AND r.is_owner
                """)
                .param("storeId", storeId.value())
                .query(Integer.class)
                .single();
    }

    /** "My stores": a cross-tenant query by user, possible only because these are directory tables. */
    public List<StoreSummary> storesOf(UUID userId) {
        return jdbc.sql("""
                SELECT s.id, s.handle, s.name, s.status
                  FROM store_members m JOIN stores s ON s.id = m.store_id
                 WHERE m.user_id = :userId
                 ORDER BY s.name, s.id
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new StoreSummary(rs.getObject("id", UUID.class), rs.getString("handle"),
                        rs.getString("name"), rs.getString("status")))
                .list();
    }

    /**
     * Row lock on the store: serializes membership changes per store so that "at least one owner"
     * cannot be broken by two concurrent demotions (write skew: each transaction sees 2 owners,
     * each removes one, both commit → 0 owners).
     */
    public void lockStore(StoreId storeId) {
        jdbc.sql("SELECT id FROM stores WHERE id = :storeId FOR UPDATE")
                .param("storeId", storeId.value())
                .query(UUID.class)
                .optional();
    }

    private static Set<Permission> permissions(ResultSet rs) throws SQLException {
        if (rs.getBoolean("is_owner")) {
            return Set.of(Permission.values());
        }
        Array array = rs.getArray("permissions");
        return Role.toPermissions((String[]) array.getArray());
    }
}
