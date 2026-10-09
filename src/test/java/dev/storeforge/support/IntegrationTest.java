package dev.storeforge.support;

import dev.storeforge.jobs.internal.JobWorker;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
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
                // Same for the storefront abuse limits (StorefrontRateLimitIT tests them in their own context).
                "storeforge.storefront.rate-limits.general.capacity=1000000",
                "storeforge.storefront.rate-limits.cart-creation.capacity=1000000",
                "storeforge.storefront.rate-limits.checkout-completion.capacity=1000000",
                "storeforge.storefront.rate-limits.discount-attempts.capacity=1000000",
                "storeforge.storefront.rate-limits.search.capacity=1000000",
                // Background work runs only when a test triggers it explicitly (no timer races).
                "storeforge.scheduling.enabled=false",
                // The fake payment provider's webhooks are delivered by the tests (to duplicate, drop, reorder them).
                "storeforge.payments.fake.webhook-url=",
                // Webhook receivers in tests are WireMock servers on http://127.0.0.1 (the SSRF guard's escape hatch).
                "storeforge.webhooks.require-https=false",
                "storeforge.webhooks.allowed-private-cidrs=127.0.0.0/8",
                "storeforge.webhooks.request-timeout=1s",
                "storeforge.webhooks.pause-after-consecutive-failures=5",
                "storeforge.jobs.type-concurrency.[test.slow]=2"
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
        RedisTestServer.registerProperties(registry);
        StorageTestServer.registerProperties(registry);
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
        RecordingEmailSender recordingEmailSender(ObjectProvider<JobWorker> jobWorker) {
            return new RecordingEmailSender(() -> jobWorker.getObject().drain());
        }

        @Bean
        static QueryCounter queryCounter() {
            return new QueryCounter();
        }

        @Bean
        TestJobHandlers.Recording recordingJobHandler() {
            return new TestJobHandlers.Recording();
        }

        @Bean
        TestJobHandlers.Slow slowJobHandler() {
            return new TestJobHandlers.Slow();
        }

        /** Captures finished spans in memory so tests can assert on trace structure. */
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }
}
