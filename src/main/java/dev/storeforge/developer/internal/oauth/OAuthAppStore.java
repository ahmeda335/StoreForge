package dev.storeforge.developer.internal.oauth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code oauth_apps}: a PLATFORM table (no RLS); apps belong to developers, not to stores. */
@Repository
@RequiredArgsConstructor
public class OAuthAppStore {

    private final JdbcClient jdbc;

    public record App(UUID id, UUID ownerUserId, String name, String clientId, String clientSecretHash, List<String> redirectUris,
                      List<String> scopes, Instant createdAt) {
    }

    public void insert(App app) {
        jdbc.sql("""
                INSERT INTO oauth_apps (id, owner_user_id, name, client_id, client_secret_hash, redirect_uris, scopes, created_at, updated_at)
                VALUES (:id, :owner, :name, :clientId, :secret, CAST(:redirects AS text[]), CAST(:scopes AS text[]), :now, :now)
                """)
                .param("id", app.id())
                .param("owner", app.ownerUserId())
                .param("name", app.name())
                .param("clientId", app.clientId())
                .param("secret", app.clientSecretHash())
                .param("redirects", app.redirectUris().toArray(String[]::new))
                .param("scopes", app.scopes().toArray(String[]::new))
                .param("now", Timestamp.from(app.createdAt()))
                .update();
    }

    public Optional<App> findByClientId(String clientId) {
        return jdbc.sql("SELECT * FROM oauth_apps WHERE client_id = :clientId").param("clientId", clientId).query(OAuthAppStore::map).optional();
    }

    public Optional<App> findById(UUID id) {
        return jdbc.sql("SELECT * FROM oauth_apps WHERE id = :id").param("id", id).query(OAuthAppStore::map).optional();
    }

    public List<App> ownedBy(UUID userId) {
        return jdbc.sql("SELECT * FROM oauth_apps WHERE owner_user_id = :owner ORDER BY created_at").param("owner", userId)
                .query(OAuthAppStore::map).list();
    }

    private static App map(ResultSet rs, int rowNum) throws SQLException {
        return new App(rs.getObject("id", UUID.class), rs.getObject("owner_user_id", UUID.class), rs.getString("name"),
                rs.getString("client_id"), rs.getString("client_secret_hash"),
                Arrays.asList((String[]) rs.getArray("redirect_uris").getArray()), Arrays.asList((String[]) rs.getArray("scopes").getArray()),
                rs.getTimestamp("created_at").toInstant());
    }
}
