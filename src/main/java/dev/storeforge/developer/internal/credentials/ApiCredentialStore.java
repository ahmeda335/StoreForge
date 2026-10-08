package dev.storeforge.developer.internal.credentials;

import dev.storeforge.developer.Scope;
import dev.storeforge.shared.tenancy.StoreId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code api_credentials}: a DIRECTORY table (no RLS). Authentication must find a credential by its
 * token hash before knowing the store; every other query here is explicitly scoped by store id.
 */
@Repository
@RequiredArgsConstructor
public class ApiCredentialStore {

    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final JdbcClient jdbc;

    public record Credential(UUID id, UUID storeId, String kind, String name, String tokenPrefix, Set<Scope> scopes,
                             @Nullable UUID createdBy, Instant createdAt, @Nullable Instant lastUsedAt, @Nullable Instant revokedAt) {
    }

    public void insert(UUID id, StoreId storeId, String kind, String name, String tokenPrefix, String tokenHash, Set<Scope> scopes,
                       @Nullable UUID createdBy, Instant now) {
        jdbc.sql("""
                INSERT INTO api_credentials (id, store_id, kind, name, token_prefix, token_hash, scopes, created_by, created_at)
                VALUES (:id, :storeId, :kind, :name, :prefix, :hash, CAST(:scopes AS text[]), :createdBy, :now)
                """)
                .param("id", id)
                .param("storeId", storeId.value())
                .param("kind", kind)
                .param("name", name)
                .param("prefix", tokenPrefix)
                .param("hash", tokenHash)
                .param("scopes", scopes.stream().map(Scope::code).sorted().toArray(String[]::new))
                .param("createdBy", createdBy)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** The authentication lookup: an active credential with this token hash (unique index). */
    public Optional<Credential> findActiveByHash(String tokenHash) {
        return jdbc.sql("SELECT * FROM api_credentials WHERE token_hash = :hash AND revoked_at IS NULL")
                .param("hash", tokenHash).query(ApiCredentialStore::map).optional();
    }

    public Optional<Credential> find(StoreId storeId, UUID id) {
        return jdbc.sql("SELECT * FROM api_credentials WHERE store_id = :storeId AND id = :id")
                .param("storeId", storeId.value()).param("id", id).query(ApiCredentialStore::map).optional();
    }

    public List<Credential> list(StoreId storeId, String kind) {
        return jdbc.sql("SELECT * FROM api_credentials WHERE store_id = :storeId AND kind = :kind ORDER BY created_at, id")
                .param("storeId", storeId.value()).param("kind", kind).query(ApiCredentialStore::map).list();
    }

    /** @return true if it was active (revocation takes effect on the very next request: nothing caches tokens) */
    public boolean revoke(StoreId storeId, UUID id, Instant now) {
        return jdbc.sql("UPDATE api_credentials SET revoked_at = :now WHERE store_id = :storeId AND id = :id AND revoked_at IS NULL")
                .param("now", Timestamp.from(now)).param("storeId", storeId.value()).param("id", id).update() == 1;
    }

    /** "Last used" without a write per request: at most one UPDATE per credential per minute. */
    public void touch(UUID id, Instant now) {
        jdbc.sql("""
                UPDATE api_credentials SET last_used_at = :now
                 WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :threshold)
                """)
                .param("now", Timestamp.from(now)).param("id", id).param("threshold", Timestamp.from(now.minus(TOUCH_INTERVAL)))
                .update();
    }

    private static Credential map(ResultSet rs, int rowNum) throws SQLException {
        Array scopes = rs.getArray("scopes");
        Timestamp lastUsed = rs.getTimestamp("last_used_at");
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new Credential(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class), rs.getString("kind"),
                rs.getString("name"), rs.getString("token_prefix"),
                Arrays.stream((String[]) scopes.getArray()).map(code -> Scope.fromCode(code).orElseThrow()).collect(Collectors.toSet()),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                lastUsed == null ? null : lastUsed.toInstant(), revoked == null ? null : revoked.toInstant());
    }
}
