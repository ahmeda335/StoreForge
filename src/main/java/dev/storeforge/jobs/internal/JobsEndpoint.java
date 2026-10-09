package dev.storeforge.jobs.internal;

import dev.storeforge.jobs.JobAdministration;
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
 * disappear silently: a human looks at them, fixes the cause, and requeues. The authenticated, audited
 * way is the platform API ({@code /platform/v1/jobs}, P14); this endpoint is for operators on the private port.
 */
@Component
@Endpoint(id = "jobs")
@Slf4j
@RequiredArgsConstructor
class JobsEndpoint {

    private final JobAdministration administration;

    @ReadOperation
    JobAdministration.Overview overview() {
        return administration.overview(50);
    }

    @WriteOperation
    Map<String, Object> requeue(@Selector UUID id) {
        boolean requeued = administration.requeue(id);
        log.atInfo().addKeyValue("jobId", id).addKeyValue("requeued", requeued).log("Operator requeue of a DEAD job");
        return Map.of("jobId", id, "requeued", requeued);
    }
}
