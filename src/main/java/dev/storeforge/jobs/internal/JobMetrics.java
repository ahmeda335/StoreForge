package dev.storeforge.jobs.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Queue health as gauges, refreshed every 15s by one cheap query each (instead of querying the
 * database on every scrape). Alert on {@code storeforge_jobs_oldest_ready_age_seconds} and DEAD
 * counts, not on depth alone: a deep queue that drains fast is fine; an old job is not.
 */
@Component
@RequiredArgsConstructor
class JobMetrics implements SmartInitializingSingleton {

    private final JobStore store;
    private final OutboxRelay relay;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<String, AtomicLong> byStatus = new ConcurrentHashMap<>();
    private final AtomicLong oldestReadyMillis = new AtomicLong();
    private final AtomicLong unpublished = new AtomicLong();

    @Override
    public void afterSingletonsInstantiated() {
        for (String status : new String[] {"READY", "RUNNING", "DEAD"}) {
            AtomicLong value = byStatus.computeIfAbsent(status, s -> new AtomicLong());
            Gauge.builder("storeforge.jobs.queue.depth", value, AtomicLong::get).tag("status", status).register(registry);
        }
        Gauge.builder("storeforge.jobs.oldest.ready.age", oldestReadyMillis, v -> v.get() / 1000.0)
                .baseUnit("seconds").register(registry);
        Gauge.builder("storeforge.outbox.unpublished", unpublished, AtomicLong::get).register(registry);
    }

    @Scheduled(fixedDelayString = "${storeforge.jobs.metrics-interval:15s}")
    void refresh() {
        store.countsByStatus().forEach((status, count) -> byStatus.computeIfAbsent(status, s -> new AtomicLong()).set(count));
        oldestReadyMillis.set((long) (store.oldestReadyAgeSeconds(clock.instant()) * 1000));
        unpublished.set(relay.unpublishedCount());
    }
}
