package dev.storeforge.jobs.internal;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * Operator view of the queue on the PRIVATE management port: {@code GET /actuator/jobs} (counts,
 * latest DEAD jobs), {@code POST /actuator/jobs/{id}} (requeue a DEAD job). Dead jobs never
 * disappear silently: a human looks at them, fixes the cause, and requeues. (Platform admin API: P14.)
 */
@Component
@Endpoint(id = "jobs")
@Slf4j
@RequiredArgsConstructor
class JobsEndpoint {

    private final JobStore store;
    private final OutboxRelay relay;
    private final Clock clock;

    record Overview(Map<String, Long> jobsByStatus, long unpublishedEvents, double oldestReadyAgeSeconds,
                    List<JobStore.DeadJob> deadJobs) {
    }

    @ReadOperation
    Overview overview() {
        return new Overview(store.countsByStatus(), relay.unpublishedCount(), store.oldestReadyAgeSeconds(clock.instant()),
                store.deadJobs(50));
    }

    @WriteOperation
    Map<String, Object> requeue(@Selector UUID id) {
        boolean requeued = store.requeue(id, clock.instant());
        log.atInfo().addKeyValue("jobId", id).addKeyValue("requeued", requeued).log("Operator requeue of a DEAD job");
        return Map.of("jobId", id, "requeued", requeued);
    }
}
