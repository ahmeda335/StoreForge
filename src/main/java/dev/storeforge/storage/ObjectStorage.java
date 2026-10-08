package dev.storeforge.storage;

import dev.storeforge.shared.tenancy.StoreId;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Object storage behind presigned URLs: clients upload and download directly, the application only
 * signs, verifies and deletes. Bytes never pass through an app instance.
 */
public interface ObjectStorage {

    /**
     * A URL that accepts exactly one PUT of {@code contentLength} bytes with header
     * {@code Content-Type: contentType} until {@code expiresAt}. Both are part of the signature: a
     * different size or type is refused by the store itself.
     */
    PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration ttl);

    /** A time-limited GET URL for one object (the bucket itself is private). */
    URI presignDownload(String key, Duration ttl);

    /** Metadata of a stored object, or empty if there is none. */
    Optional<StoredObject> head(String key);

    /** The first {@code length} bytes (fewer if the object is shorter), e.g. to check a file signature. */
    byte[] readPrefix(String key, int length);

    /** Idempotent: deleting a missing key succeeds. */
    void delete(String key);

    /**
     * Deletes the object reliably after the caller's transaction commits (a job, retried with
     * backoff). MANDATORY transaction: the row that referenced the key goes in the same commit.
     */
    void deleteLater(StoreId storeId, String key);

    record PresignedUpload(URI url, String method, String contentType, long contentLength, Instant expiresAt) {
    }

    record StoredObject(long size, String contentType) {
    }
}
