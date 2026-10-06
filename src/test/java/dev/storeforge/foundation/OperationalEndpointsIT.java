package dev.storeforge.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class OperationalEndpointsIT extends IntegrationTest {

    @Test
    void livenessIsUpAndDoesNotDependOnTheDatabase() {
        HttpResponse<String> response = management.get("/actuator/health/liveness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("\"db\"");
    }

    @Test
    void readinessIncludesTheDatabase() {
        HttpResponse<String> response = management.get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").contains("\"db\"");
    }

    @Test
    void prometheusMetricsAreExposedOnTheManagementPort() {
        api.get("/does-not-exist"); // produce at least one HTTP server observation

        HttpResponse<String> response = management.get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("http_server_requests_seconds_bucket")
                .contains("hikaricp_connections_active")
                .contains("application=\"storeforge\"");
    }

    @Test
    void actuatorIsNotReachableOnThePublicPort() {
        HttpResponse<String> response = api.get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(404);
    }
}
