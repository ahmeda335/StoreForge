package dev.storeforge.storage.internal;

import dev.storeforge.jobs.JobHandler;
import dev.storeforge.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Deletes one object; storage errors throw, so the job retries with backoff. Deleting twice is harmless. */
@Component
@RequiredArgsConstructor
class ObjectDeletionJob implements JobHandler {

    private final ObjectStorage storage;

    @Override
    public String type() {
        return S3ObjectStorage.DELETE_JOB;
    }

    @Override
    public void handle(Job job) {
        storage.delete(job.payload().get("key").asString());
    }
}
