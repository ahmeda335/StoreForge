package dev.storeforge.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

/** Load shedding (EXP-023): beyond the limit, a fast 503 instead of an unbounded queue. */
class ConcurrencyLimitFilterTest {

    @Test
    void requestsBeyondTheLimitAreShedFastAndSlotsComeBack() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new ConcurrencyLimitFilter(
                new HttpEdgeProperties(DataSize.ofMegabytes(1), List.of(), 1, Duration.ofMillis(20)), JsonMapper.builder().build(), registry);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var busy = executor.submit(() -> {
                filter.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(), (req, res) -> {
                    inside.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            });
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            MockHttpServletResponse shed = new MockHttpServletResponse();
            long started = System.nanoTime();
            filter.doFilter(new MockHttpServletRequest("GET", "/y"), shed, (req, res) -> {
                throw new AssertionError("must not run");
            });
            assertThat(shed.getStatus()).isEqualTo(503);
            assertThat(shed.getHeader("Retry-After")).isEqualTo("1");
            assertThat(shed.getContentAsString()).contains("\"code\":\"overloaded\"");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("fast, not queued").isLessThan(Duration.ofSeconds(1));
            assertThat(registry.get("storeforge.http.requests.in_flight").gauge().value()).isEqualTo(1);

            release.countDown();
            busy.get(5, TimeUnit.SECONDS);
        }
        MockHttpServletResponse after = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/z"), after, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        assertThat(after.getStatus()).as("the slot was released").isEqualTo(204);
        assertThat(registry.get("storeforge.http.requests.shed").counter().count()).isEqualTo(1);
    }
}
