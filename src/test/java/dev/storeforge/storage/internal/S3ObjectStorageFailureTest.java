package dev.storeforge.storage.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.storeforge.storage.StorageUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** EXP-025: with object storage down, finishing an upload answered 500. A dependency outage is a 503. */
class S3ObjectStorageFailureTest {

    @Test
    void unreachableStorageIsUnavailable() {
        var storage = storageAt(URI.create("http://127.0.0.1:1"));   // nothing listens on port 1

        assertThatThrownBy(() -> storage.head("stores/x/images/y"))
                .isInstanceOfSatisfying(StorageUnavailableException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void serverErrorsAreUnavailableButClientErrorsAreNot() throws Exception {
        assertThatThrownBy(() -> withServerAnswering(502, storage -> storage.head("k"))).isInstanceOf(StorageUnavailableException.class);
        // 403 is our misconfiguration (wrong keys, policy), not an outage: it stays an unexpected error.
        assertThatThrownBy(() -> withServerAnswering(403, storage -> storage.head("k")))
                .isInstanceOf(S3Exception.class).isNotInstanceOf(StorageUnavailableException.class);
    }

    private static void withServerAnswering(int status, Consumer<S3ObjectStorage> call) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            call.accept(storageAt(URI.create("http://127.0.0.1:" + server.getAddress().getPort())));
        } finally {
            server.stop(0);
        }
    }

    private static S3ObjectStorage storageAt(URI endpoint) {
        var properties = new StorageProperties(endpoint, null, "us-east-1", "bucket", "key", "secret", true, false);
        return new S3ObjectStorage(properties, null, new SimpleMeterRegistry(), Clock.systemUTC());
    }
}
