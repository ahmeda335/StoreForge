package dev.storeforge.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One real PostgreSQL container shared by all integration tests in the JVM (singleton container
 * pattern: started once, stopped by Testcontainers' Ryuk when the JVM exits).
 *
 * <p>It is provisioned with the SAME init script as Docker Compose, so tests run with the same two
 * roles as every other environment: Flyway as {@code storeforge_owner}, the app as
 * {@code storeforge_app}. Testing as a superuser would hide privilege and (later) RLS bugs.
 */
public final class PostgresTestDatabase {

    private static final String OWNER_PASSWORD = "owner_test";
    private static final String APP_PASSWORD = "app_test";

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("storeforge")
            .withEnv("STOREFORGE_OWNER_PASSWORD", OWNER_PASSWORD)
            .withEnv("STOREFORGE_APP_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath("infra/postgres/init/01-create-roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-create-roles.sh");

    static {
        CONTAINER.start();
    }

    private PostgresTestDatabase() {
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "storeforge_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> "storeforge_owner");
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
    }
}
