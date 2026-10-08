package dev.storeforge.support;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * One real S3-compatible object store (SeaweedFS, the same as in compose.yaml) shared by all
 * integration tests. Unauthenticated requests get 403: that is the "up" signal.
 */
public final class StorageTestServer {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> CONTAINER = new GenericContainer<>("chrislusf/seaweedfs:3.97")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("infra/seaweedfs/s3.json")), "/etc/seaweedfs/s3.json")
            .withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data", "-volume.max=0", "-master.volumeSizeLimitMB=64")
            .withExposedPorts(8333)
            .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403));

    static {
        CONTAINER.start();
    }

    private StorageTestServer() {
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("storeforge.storage.endpoint", () -> "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8333));
    }
}
