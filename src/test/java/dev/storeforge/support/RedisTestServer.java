package dev.storeforge.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/**
 * One real Redis shared by all integration tests (same pattern as {@link PostgresTestDatabase}).
 * Tests that simulate a Redis outage PAUSE the container (the process freezes: connections stay open
 * but nothing answers, the nastiest kind of failure) and always unpause it.
 */
public final class RedisTestServer {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> CONTAINER = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static {
        CONTAINER.start();
    }

    private RedisTestServer() {
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("storeforge.redis.url", () -> "redis://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(6379));
    }

    public static void pause() {
        CONTAINER.getDockerClient().pauseContainerCmd(CONTAINER.getContainerId()).exec();
    }

    public static void unpause() {
        CONTAINER.getDockerClient().unpauseContainerCmd(CONTAINER.getContainerId()).exec();
    }
}
