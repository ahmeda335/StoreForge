package dev.storeforge.support;

import dev.storeforge.jobs.JobHandler;
import dev.storeforge.jobs.PermanentJobFailure;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Job handlers that exist only in tests, to exercise retries, dead-lettering and concurrency. */
public final class TestJobHandlers {

    private TestJobHandlers() {
    }

    /** Counts executions per job; fails the first {@code failTimes} attempts (payload), "permanent" fails for good. */
    public static class Recording implements JobHandler {

        public static final String TYPE = "test.recording";
        private final Map<UUID, AtomicInteger> executions = new ConcurrentHashMap<>();

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public void handle(Job job) {
            int execution = executions.computeIfAbsent(job.id(), id -> new AtomicInteger()).incrementAndGet();
            if (job.payload().path("permanent").asBoolean(false)) {
                throw new PermanentJobFailure("bad input: " + job.payload().path("label").asString(""));
            }
            if (execution <= job.payload().path("failTimes").asInt(0)) {
                throw new IllegalStateException("transient failure #" + execution);
            }
        }

        public int executions(UUID jobId) {
            return executions.getOrDefault(jobId, new AtomicInteger()).get();
        }
    }

    /** Takes {@code millis} (payload); remembers how many ran at the same time, at most. */
    public static class Slow implements JobHandler {

        public static final String TYPE = "test.slow";
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger maxRunning = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public void handle(Job job) {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(job.payload().path("millis").asLong(500));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
                finished.incrementAndGet();
            }
        }

        public int maxRunning() {
            return maxRunning.get();
        }

        public int finished() {
            return finished.get();
        }

        public void reset() {
            maxRunning.set(0);
            finished.set(0);
        }
    }
}
