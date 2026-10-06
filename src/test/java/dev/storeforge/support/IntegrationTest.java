package dev.storeforge.support;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests: full application context, real HTTP server on a random port
 * (and a random management port), real PostgreSQL, emails captured in memory.
 *
 * <p>All IT classes share ONE configuration so Spring caches and reuses one application context.
 * Tests therefore must not depend on global state: each test uses unique emails (see
 * {@link #uniqueEmail()}) and later unique stores.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // All test requests come from 127.0.0.1; the per-IP limit is exercised in unit tests.
                "storeforge.auth.rate-limits.per-ip=100000",
                // Background work runs only when a test triggers it explicitly (no timer races).
                "storeforge.scheduling.enabled=false"
        })
@Import(IntegrationTest.TestBeans.class)
public abstract class IntegrationTest {

    @Autowired
    protected Environment environment;

    @Autowired
    protected RecordingEmailSender mailbox;

    protected HttpTestClient api;
    protected HttpTestClient management;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.registerProperties(registry);
    }

    @BeforeEach
    void httpClients() {
        api = new HttpTestClient("http://localhost:" + environment.getProperty("local.server.port"));
        management = new HttpTestClient("http://localhost:" + environment.getProperty("local.management.port"));
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }

        @Bean
        static QueryCounter queryCounter() {
            return new QueryCounter();
        }

        /** Captures finished spans in memory so tests can assert on trace structure. */
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }
}
