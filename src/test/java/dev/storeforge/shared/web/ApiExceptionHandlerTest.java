package dev.storeforge.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.tracing.Tracer;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** EXP-025: database outages, lock waits and lost connections answered 500; they are 503 + Retry-After. */
class ApiExceptionHandlerTest {

    private static final Map<String, Supplier<RuntimeException>> FAILURES = Map.of(
            "no-connection", () -> new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", new SQLException("refused", "08001")),
            "no-transaction", () -> new CannotCreateTransactionException("Could not open JPA EntityManager"),
            "statement-timeout", () -> new QueryTimeoutException("canceling statement due to statement timeout"),
            "lock-timeout", () -> new CannotAcquireLockException("could not obtain lock"),
            "rollback-on-dead-connection", () -> new JpaSystemException(new RuntimeException("Unable to rollback against JDBC Connection",
                    new SQLException("This connection has been closed.", "08003"))),
            "rollback-on-evicted-connection", () -> new JpaSystemException(new RuntimeException("Unable to rollback against JDBC Connection",
                    new SQLException("Connection is closed"))),
            "bug", () -> new IllegalStateException("bug"));

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Failing())
            .setControllerAdvice(new ApiExceptionHandler(Tracer.NOOP))
            .build();

    @ParameterizedTest
    @ValueSource(strings = {"no-connection", "no-transaction", "statement-timeout", "lock-timeout", "rollback-on-dead-connection",
            "rollback-on-evicted-connection"})
    void databaseTroubleIsUnavailableNotBroken(String failure) throws Exception {
        mvc.perform(get("/fail/" + failure))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("service_unavailable"));
    }

    @Test
    void bugsStayInternalErrors() throws Exception {
        mvc.perform(get("/fail/bug")).andExpect(status().isInternalServerError()).andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void recognisesALostConnectionThroughAnyWrapping() {
        assertThat(ApiExceptionHandler.lostDatabaseConnection(FAILURES.get("rollback-on-dead-connection").get())).isTrue();
        assertThat(ApiExceptionHandler.lostDatabaseConnection(new RuntimeException(new SQLException("duplicate key", "23505")))).isFalse();
        assertThat(ApiExceptionHandler.lostDatabaseConnection(new IllegalStateException("bug"))).isFalse();
        assertThat(ApiExceptionHandler.lostDatabaseConnection(new SQLException("Connection is closed", "23505"))).as("a state wins over the text").isFalse();
    }

    @RestController
    static class Failing {
        @GetMapping("/fail/{failure}")
        String fail(@PathVariable String failure) {
            throw FAILURES.get(failure).get();
        }
    }
}
