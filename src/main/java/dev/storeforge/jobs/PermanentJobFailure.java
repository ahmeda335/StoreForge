package dev.storeforge.jobs;

/**
 * Thrown by handlers when retrying cannot help (invalid data, missing entity): the job goes to DEAD
 * immediately instead of burning its retries. Everything else is treated as transient and retried.
 */
public class PermanentJobFailure extends RuntimeException {

    public PermanentJobFailure(String message) {
        super(message);
    }
}
