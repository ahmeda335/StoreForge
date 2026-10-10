package dev.storeforge.shared.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

class SchemaVersionGuardTest {

    @Test
    void knowsTheNewestMigrationShippedWithTheCode() {
        assertThat(SchemaVersionGuard.latestMigrationInCode()).isGreaterThanOrEqualTo(22);
    }

    @Test
    void refusesAnOlderSchemaAndAcceptsTheSameOrNewer() {
        int code = SchemaVersionGuard.latestMigrationInCode();
        assertThatThrownBy(() -> guardAt(code - 1).afterSingletonsInstantiated()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run the migrate job first");
        guardAt(code).afterSingletonsInstantiated();
        guardAt(code + 1).afterSingletonsInstantiated();   // expand/contract: the previous release runs on a newer schema
    }

    @Test
    void anUnreadableHistoryMeansASchemaFromBeforeTheGrant() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(anyString()).query(Integer.class).single()).thenThrow(new PermissionDeniedDataAccessException("denied", null));
        assertThatThrownBy(() -> new SchemaVersionGuard(jdbc).afterSingletonsInstantiated()).hasMessageContaining("is at V0");
    }

    private static SchemaVersionGuard guardAt(int version) {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(anyString()).query(Integer.class).single()).thenReturn(version);
        return new SchemaVersionGuard(jdbc);
    }
}
