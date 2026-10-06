package dev.storeforge.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.shared.web.RequestIds;
import dev.storeforge.support.IntegrationTest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Import(ErrorResponseIT.FailingController.class)
@ExtendWith(OutputCaptureExtension.class)
class ErrorResponseIT extends IntegrationTest {

    @Test
    void unknownPathReturnsProblemDetailsWithCodeAndRequestId() {
        HttpResponse<String> response = api.get("/no/such/resource");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        String requestId = response.headers().firstValue(RequestIds.HEADER).orElseThrow();
        assertThat(response.body())
                .contains("\"status\":404")
                .contains("\"code\":\"not_found\"")
                .contains("\"type\":\"https://docs.storeforge.dev/errors/not-found\"")
                .contains("\"requestId\":\"" + requestId + "\"");
    }

    @Test
    void wellFormedIncomingRequestIdIsPropagated() {
        HttpResponse<String> response = api.get("/no/such/resource", Map.of(RequestIds.HEADER, "client-trace-42"));

        assertThat(response.headers().firstValue(RequestIds.HEADER)).hasValue("client-trace-42");
        assertThat(response.body()).contains("\"requestId\":\"client-trace-42\"");
    }

    @Test
    void malformedIncomingRequestIdIsReplaced() {
        HttpResponse<String> response = api.get("/no/such/resource", Map.of(RequestIds.HEADER, "bad id!"));

        assertThat(response.headers().firstValue(RequestIds.HEADER))
                .hasValueSatisfying(id -> assertThat(id).isNotEqualTo("bad id!").matches("[0-9a-f]{32}"));
    }

    @Test
    void unexpectedExceptionBecomesOpaque500AndIsLoggedWithTheRequestId(CapturedOutput output) {
        HttpResponse<String> response = api.get("/test/failure");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body())
                .contains("\"code\":\"internal_error\"")
                .doesNotContain("secret-internal-detail")   // exception message must not leak
                .doesNotContain("FailingController")         // nor class names / stack traces
                .doesNotContain("at dev.storeforge");

        String requestId = response.headers().firstValue(RequestIds.HEADER).orElseThrow();
        String traceId = response.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(response.body()).contains("\"traceId\":\"" + traceId + "\"");
        assertThat(output.getOut())
                .as("the log line carries the trace id, linking logs and traces")
                .contains("\"traceId\":\"" + traceId + "\"");
        assertThat(output.getOut())
                .as("the ERROR log line is JSON and carries the request id")
                .contains("\"requestId\":\"" + requestId + "\"")
                .contains("secret-internal-detail");      // ...but the full detail IS in the server log
    }

    @RestController
    static class FailingController {

        @GetMapping("/test/failure")
        String fail() {
            throw new IllegalStateException("secret-internal-detail");
        }
    }
}
