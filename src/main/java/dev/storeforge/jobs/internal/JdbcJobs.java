package dev.storeforge.jobs.internal;

import dev.storeforge.jobs.Jobs;
import dev.storeforge.shared.tenancy.StoreId;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
class JdbcJobs implements Jobs {

    private final JobStore store;
    private final JsonMapper jsonMapper;
    private final TraceContexts traces;
    private final JobsProperties properties;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(@Nullable StoreId storeId, String type, String dedupKey, Object payload) {
        store.insert(storeId == null ? null : storeId.value(), type, dedupKey, null, jsonMapper.writeValueAsString(payload),
                properties.maxAttempts(), traces.currentTraceParent(), clock.instant());
    }
}
