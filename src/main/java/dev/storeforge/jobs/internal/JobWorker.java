package dev.storeforge.jobs.internal;

import dev.storeforge.jobs.Event;
import dev.storeforge.jobs.EventHandler;
import dev.storeforge.jobs.JobHandler;
import dev.storeforge.jobs.JobHandler.Job;
import dev.storeforge.jobs.PermanentJobFailure;
import dev.storeforge.jobs.internal.JobStore.ClaimedJob;
import dev.storeforge.shared.tenancy.StoreId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claims jobs and runs their handlers. One cycle: relay outbox events → claim as many jobs as can
 * START NOW (short committed transaction) → run each handler on its own virtual thread, OUTSIDE the
 * claim transaction, inside a span continuing the producer's trace → DONE / retry with backoff / DEAD.
 *
 * <p>Concurrency (P11, EXP-012): handlers used to run one after another, so one slow webhook
 * receiver (10 s per attempt) delayed every email behind it, and jobs waiting in a claimed batch
 * outlived their lease and were run AGAIN by another instance. Now: a total limit per instance,
 * per-type limits (bulkheads) so one kind of slow work can't take every slot, and only jobs that
 * start immediately are claimed (a lease never ticks while a job waits locally).
 *
 * <p>Delivery semantics: AT LEAST ONCE. A worker can crash after the handler's effect but before
 * {@code markDone}; the lease expires and another worker runs the job again. Handlers are idempotent.
 */
@Component
@Slf4j
public class JobWorker {

    static final String EVENT_PREFIX = "event:";

    private final JobStore store;
    private final OutboxRelay relay;
    private final JdbcClient jdbc;
    private final TraceContexts traces;
    private final Tracer tracer;
    private final JsonMapper jsonMapper;
    private final JobsProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Map<String, JobHandler> jobHandlers;
    private final Map<String, EventHandler> eventHandlers;
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Map<String, AtomicInteger> inFlightByType = new ConcurrentHashMap<>();

    JobWorker(JobStore store, OutboxRelay relay, JdbcClient jdbc, TraceContexts traces, Tracer tracer, JsonMapper jsonMapper,
              JobsProperties properties, MeterRegistry meterRegistry, Clock clock, List<JobHandler> jobHandlers,
              List<EventHandler> eventHandlers) {
        this.store = store;
        this.relay = relay;
        this.jdbc = jdbc;
        this.traces = traces;
        this.tracer = tracer;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.jobHandlers = jobHandlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.eventHandlers = eventHandlers.stream().collect(Collectors.toMap(EventHandler::name, Function.identity()));
    }

    /** Does not wait for the jobs it starts: the next poll can start more while slow ones run. */
    @Scheduled(fixedDelayString = "${storeforge.jobs.poll-interval}", initialDelayString = "${storeforge.jobs.poll-interval}")
    void poll() {
        relay.publishOnce();
        start(clock.instant());
    }

    /** Relays and runs until nothing is due. Used by tests and local tooling instead of waiting for the poller. */
    public void drain() {
        for (int round = 0; round < 100; round++) {
            int published = relay.publishOnce();
            int processed = runOnce(clock.instant());
            if (published == 0 && processed == 0) {
                return;
            }
        }
        log.warn("Job drain stopped after 100 rounds; work keeps arriving");
    }

    /**
     * Claims, runs and WAITS for the jobs it started (tests and tooling).
     *
     * @param now time used for claiming, leases and backoff (a parameter so tests can move time forward)
     */
    public int runOnce(Instant now) {
        List<Future<?>> started = start(now);
        for (Future<?> job : started) {
            try {
                job.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                log.error("Unexpected error running a job", e.getCause());
            }
        }
        return started.size();
    }

    private List<Future<?>> start(Instant now) {
        int free = properties.concurrency() - inFlight.get();
        if (free <= 0) {
            return List.of();
        }
        Set<String> saturated = new HashSet<>();
        properties.typeConcurrency().forEach((type, limit) -> {
            if (counter(type).get() >= limit) {
                saturated.add(type);
            }
        });
        List<ClaimedJob> claimed = store.claim(workerId, now, now.plus(properties.lease()), Math.min(free, properties.batchSize()), saturated);
        List<Future<?>> started = new ArrayList<>();
        for (ClaimedJob job : claimed) {
            // Reserve a slot first, then check: concurrent callers can't both take the last one.
            AtomicInteger ofType = counter(job.type());
            Integer typeLimit = properties.typeConcurrency().get(job.type());
            boolean overTotal = inFlight.incrementAndGet() > properties.concurrency();
            boolean overType = ofType.incrementAndGet() > (typeLimit == null ? Integer.MAX_VALUE : typeLimit);
            if (overTotal || overType) {
                inFlight.decrementAndGet();
                ofType.decrementAndGet();
                store.release(job.id(), workerId, clock.instant());
                continue;
            }
            started.add(executor.submit(() -> {
                try {
                    execute(job, now);
                } finally {
                    inFlight.decrementAndGet();
                    ofType.decrementAndGet();
                }
            }));
        }
        return started;
    }

    private AtomicInteger counter(String type) {
        return inFlightByType.computeIfAbsent(type, key -> new AtomicInteger());
    }

    /** Lets running jobs finish on shutdown; anything cut off is re-run after its lease (at-least-once). */
    @PreDestroy
    void stop() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
            log.warn("Jobs still running at shutdown; they will be re-run after their lease expires");
            executor.shutdownNow();
        }
    }

    private void execute(ClaimedJob job, Instant now) {
        Span span = traces.startConsumerSpan("job " + job.type(), job.traceParent())
                .tag("storeforge.job.type", job.type())
                .tag("storeforge.job.attempt", Integer.toString(job.attempts()));
        MDC.put("jobId", job.id().toString());
        MDC.put("jobType", job.type());
        Tracer.SpanInScope scope = tracer.withSpan(span);
        try {
            dispatch(job);
            store.markDone(job.id(), workerId, clock.instant());
            count(job, "done");
        } catch (RuntimeException e) {
            span.error(e);
            fail(job, e, now);
        } finally {
            scope.close();
            span.end();
            MDC.remove("jobId");
            MDC.remove("jobType");
        }
    }

    private void dispatch(ClaimedJob job) {
        StoreId storeId = job.storeId() == null ? null : StoreId.of(job.storeId());
        JsonNode payload = jsonMapper.readTree(job.payload());
        if (job.type().startsWith(EVENT_PREFIX)) {
            EventHandler handler = eventHandlers.get(job.type().substring(EVENT_PREFIX.length()));
            if (handler == null) {
                throw new PermanentJobFailure("No event handler for " + job.type());
            }
            handler.handle(loadEvent(UUID.fromString(payload.get("eventId").asString())));
            return;
        }
        JobHandler handler = jobHandlers.get(job.type());
        if (handler == null) {
            throw new PermanentJobFailure("No job handler for " + job.type());
        }
        handler.handle(new Job(job.id(), storeId, job.type(), payload, job.attempts()));
    }

    private Event loadEvent(UUID eventId) {
        return jdbc.sql("""
                SELECT id, store_id, aggregate_type, aggregate_id, event_type, event_version, occurred_at, payload::text AS payload
                  FROM outbox_events WHERE id = :id
                """)
                .param("id", eventId)
                .query((rs, rowNum) -> new Event(rs.getObject("id", UUID.class),
                        rs.getObject("store_id", UUID.class) == null ? null : StoreId.of(rs.getObject("store_id", UUID.class)),
                        rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getString("event_type"),
                        rs.getInt("event_version"), rs.getTimestamp("occurred_at").toInstant(), jsonMapper.readTree(rs.getString("payload"))))
                .optional()
                .orElseThrow(() -> new PermanentJobFailure("Event " + eventId + " no longer exists"));
    }

    private void fail(ClaimedJob job, RuntimeException e, Instant now) {
        String error = (e.getClass().getSimpleName() + ": " + e.getMessage());
        error = error.length() > 1000 ? error.substring(0, 1000) : error;
        boolean permanent = e instanceof PermanentJobFailure;
        if (permanent || job.attempts() >= job.maxAttempts()) {
            store.markDead(job.id(), workerId, error, clock.instant());
            count(job, "dead");
            // jobId/jobType come from the MDC. Adding them again as key-values makes the JSON encoder
            // reject the event as a duplicate key, and the line is silently dropped (found in P9 testing).
            log.atError().addKeyValue("attempts", job.attempts())
                    .setCause(e).log(permanent ? "Job failed permanently; moved to DEAD" : "Job exhausted its retries; moved to DEAD");
            return;
        }
        Duration delay = Backoff.delay(job.attempts(), properties.backoffBase(), properties.backoffCap(), ThreadLocalRandom.current());
        store.markRetry(job.id(), workerId, now.plus(delay), error, clock.instant());
        count(job, "retry");
        log.atWarn().addKeyValue("attempt", job.attempts())
                .addKeyValue("retryInSeconds", delay.toSeconds()).log("Job failed; will retry: " + error);
    }

    private void count(ClaimedJob job, String outcome) {
        meterRegistry.counter("storeforge.jobs.executions", "type", job.type(), "outcome", outcome).increment();
    }
}
