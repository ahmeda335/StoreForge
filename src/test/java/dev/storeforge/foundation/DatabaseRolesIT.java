package dev.storeforge.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.storeforge.support.IntegrationTest;
import java.sql.SQLException;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The runtime role must be able to use the schema but never change it (ADR-006). These tests run as
 * the real app role against a real PostgreSQL provisioned by the same init script as Docker Compose.
 */
class DatabaseRolesIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Flyway flyway;

    @Test
    void applicationConnectsAsTheNonOwnerAppRole() {
        String currentUser = jdbc.sql("SELECT current_user").query(String.class).single();
        String schema = jdbc.sql("SELECT current_schema()").query(String.class).single();

        assertThat(currentUser).isEqualTo("storeforge_app");
        assertThat(schema).isEqualTo("storeforge");
    }

    @Test
    void migrationsWereAppliedByTheOwnerRole() {
        var applied = Arrays.stream(flyway.info().applied()).toList();

        assertThat(applied).isNotEmpty().allSatisfy(migration -> {
            assertThat(migration.getState().isFailed()).isFalse();
            assertThat(migration.getInstalledBy()).isEqualTo("storeforge_owner");
        });
    }

    @Test
    void appRoleCannotCreateTables() {
        assertThatThrownBy(() -> jdbc.sql("CREATE TABLE should_not_exist (id int)").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class, cause ->
                        // 42501 = insufficient_privilege
                        assertThat(cause.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void appRoleHasDatabaseSideTimeouts() {
        String statementTimeout = jdbc.sql("SHOW statement_timeout").query(String.class).single();
        String idleInTransactionTimeout = jdbc.sql("SHOW idle_in_transaction_session_timeout").query(String.class).single();

        assertThat(statementTimeout).isEqualTo("10s");
        assertThat(idleInTransactionTimeout).isEqualTo("30s");
    }
}
