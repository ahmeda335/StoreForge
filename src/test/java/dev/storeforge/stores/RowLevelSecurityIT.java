package dev.storeforge.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.support.IntegrationTest;
import dev.storeforge.support.StoreFlows;
import dev.storeforge.support.StoreFlows.Owner;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database layer of tenant isolation, tested directly as the application role: what happens
 * when application code FORGETS a store filter. Expected answer: nothing leaks.
 */
class RowLevelSecurityIT extends IntegrationTest {

    /** Tenant-directory tables carry store_id but are deliberately not under RLS (ADR-009). Keep this list tiny. */
    private static final Set<String> DIRECTORY_TABLES = Set.of("store_domains", "store_members");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantTransactions tenant;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    DataSource dataSource;

    Owner a;
    Owner b;

    @BeforeEach
    void twoTenants() {
        StoreFlows stores = new StoreFlows(api, mailbox);
        a = stores.newOwnerWithStore(uniqueEmail());
        b = stores.newOwnerWithStore(uniqueEmail());
    }

    @Test
    void everyTableWithAStoreIdColumnHasForcedRowLevelSecurityAndAPolicy() {
        record TableSecurity(String table, boolean enabled, boolean forced, int policies) {
        }
        List<TableSecurity> tables = jdbc.sql("""
                SELECT c.relname AS table_name, c.relrowsecurity, c.relforcerowsecurity,
                       (SELECT count(*) FROM pg_policies p WHERE p.schemaname = n.nspname AND p.tablename = c.relname) AS policies
                  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'storeforge' AND c.relkind = 'r'
                   AND EXISTS (SELECT 1 FROM information_schema.columns col
                                WHERE col.table_schema = n.nspname AND col.table_name = c.relname AND col.column_name = 'store_id')
                """)
                .query((rs, rowNum) -> new TableSecurity(rs.getString("table_name"), rs.getBoolean("relrowsecurity"),
                        rs.getBoolean("relforcerowsecurity"), rs.getInt("policies")))
                .list();

        assertThat(tables).extracting(TableSecurity::table).contains("roles", "invitations", "audit_log");
        assertThat(tables).filteredOn(table -> !DIRECTORY_TABLES.contains(table.table()))
                .allSatisfy(table -> {
                    assertThat(table.enabled()).as(table.table() + " RLS enabled").isTrue();
                    assertThat(table.forced()).as(table.table() + " RLS forced").isTrue();
                    assertThat(table.policies()).as(table.table() + " policies").isPositive();
                });
    }

    @Test
    void withoutTenantContextTenantDataIsInvisible() {
        Integer visible = transaction.execute(status -> countRoles(a.storeId(), b.storeId()));

        assertThat(visible).isZero();
    }

    @Test
    void tenantContextShowsOnlyThatTenantEvenWhenQueryingForTheOther() {
        List<UUID> storesSeen = tenant.inStore(StoreId.of(a.storeId()), () ->
                jdbc.sql("SELECT store_id FROM roles WHERE store_id IN (:a, :b)")
                        .param("a", a.storeId()).param("b", b.storeId())
                        .query(UUID.class).list());
        Integer explicitlyB = tenant.inStore(StoreId.of(a.storeId()), () -> jdbc.sql("SELECT count(*) FROM roles WHERE store_id = :b")
                .param("b", b.storeId()).query(Integer.class).single());

        assertThat(storesSeen).isNotEmpty().containsOnly(a.storeId());
        assertThat(explicitlyB).isZero();
    }

    @Test
    void writingRowsForAnotherTenantIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> tenant.runInStore(StoreId.of(a.storeId()), () ->
                jdbc.sql("""
                        INSERT INTO roles (id, store_id, name, is_owner, permissions, created_at, updated_at, version)
                        VALUES (:id, :storeB, 'Injected', false, '{}', now(), now(), 0)
                        """)
                        .param("id", UUID.randomUUID()).param("storeB", b.storeId()).update()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void tenantContextEndsWithItsTransactionOnThePooledConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.current_store_id', '" + a.storeId() + "', true)");
                assertThat(count(statement, "SELECT count(*) FROM roles")).isPositive();
                connection.commit();

                // Same physical connection, next transaction: the setting is gone (SET LOCAL semantics).
                assertThat(count(statement, "SELECT count(*) FROM roles")).isZero();
                connection.commit();
            }
        }
    }

    @Test
    void aTransactionCannotSwitchTenants() {
        assertThatThrownBy(() -> tenant.runInStore(StoreId.of(a.storeId()), () ->
                tenant.runInStore(StoreId.of(b.storeId()), () -> { })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another store");
    }

    @Test
    void theAuditLogIsAppendOnlyForTheApplication() {
        for (String statement : List.of("UPDATE audit_log SET action = 'forged'", "DELETE FROM audit_log")) {
            assertThatThrownBy(() -> tenant.runInStore(StoreId.of(a.storeId()), () -> jdbc.sql(statement).update()))
                    .as(statement)
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("permission denied");
        }
    }

    private Integer countRoles(UUID first, UUID second) {
        return jdbc.sql("SELECT count(*) FROM roles WHERE store_id IN (:a, :b)")
                .param("a", first).param("b", second).query(Integer.class).single();
    }

    private static int count(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
