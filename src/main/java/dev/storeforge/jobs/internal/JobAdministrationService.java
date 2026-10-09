package dev.storeforge.jobs.internal;

import dev.storeforge.jobs.JobAdministration;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class JobAdministrationService implements JobAdministration {

    private final JobStore store;
    private final OutboxRelay relay;
    private final Clock clock;

    @Override
    public Overview overview(int deadJobLimit) {
        return new Overview(store.countsByStatus(), relay.unpublishedCount(), store.oldestReadyAgeSeconds(clock.instant()),
                store.deadJobs(deadJobLimit).stream()
                        .map(j -> new DeadJob(j.id(), j.type(), j.storeId(), j.attempts(), j.lastError(), j.updatedAt())).toList());
    }

    @Override
    public boolean requeue(UUID jobId) {
        return store.requeue(jobId, clock.instant());
    }
}
