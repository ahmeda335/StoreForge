package dev.storeforge.storage.internal;

import dev.storeforge.jobs.Jobs;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.storage.ObjectStorage;
import dev.storeforge.storage.StorageUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * {@link ObjectStorage} on the S3 API (AWS SDK v2). Every call is timed as
 * {@code storeforge.storage.requests{operation, outcome}}.
 */
@Component
@Slf4j
class S3ObjectStorage implements ObjectStorage {

    static final String DELETE_JOB = "storage.delete-object";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Jobs jobs;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    S3ObjectStorage(StorageProperties properties, Jobs jobs, MeterRegistry meterRegistry, Clock clock) {
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        var s3Config = S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyle()).build();
        this.s3 = S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(3)))
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(properties.clientEndpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
        this.bucket = properties.bucket();
        this.jobs = jobs;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        if (properties.createBucket()) {
            createBucketIfMissing();
        }
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration ttl) {
        var presigned = presigner.presignPutObject(r -> r.signatureDuration(ttl)
                .putObjectRequest(o -> o.bucket(bucket).key(key).contentType(contentType).contentLength(contentLength)));
        return new PresignedUpload(URI.create(presigned.url().toString()), "PUT", contentType, contentLength, clock.instant().plus(ttl));
    }

    @Override
    public URI presignDownload(String key, Duration ttl) {
        return URI.create(presigner.presignGetObject(r -> r.signatureDuration(ttl).getObjectRequest(o -> o.bucket(bucket).key(key)))
                .url().toString());
    }

    @Override
    public Optional<StoredObject> head(String key) {
        return timed("head", () -> {
            try {
                HeadObjectResponse response = s3.headObject(r -> r.bucket(bucket).key(key));
                return Optional.of(new StoredObject(response.contentLength(), response.contentType()));
            } catch (NoSuchKeyException e) {
                return Optional.empty();
            } catch (S3Exception e) {
                if (e.statusCode() == 404) {
                    return Optional.empty();
                }
                throw e;
            }
        });
    }

    @Override
    public byte[] readPrefix(String key, int length) {
        return timed("read", () -> s3.getObject(r -> r.bucket(bucket).key(key).range("bytes=0-" + (length - 1)),
                ResponseTransformer.toBytes()).asByteArray());
    }

    @Override
    public void delete(String key) {
        timed("delete", () -> s3.deleteObject(r -> r.bucket(bucket).key(key)));
    }

    @Override
    public void deleteLater(StoreId storeId, String key) {
        jobs.enqueue(storeId, DELETE_JOB, key, Map.of("key", key));
    }

    /**
     * Local development and tests only. Best effort: storage being down must not stop the whole
     * application from starting (ADR-024: an outage affects uploads only; found in P15).
     */
    private void createBucketIfMissing() {
        try {
            s3.headBucket(r -> r.bucket(bucket));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                log.atWarn().addKeyValue("bucket", bucket).addKeyValue("status", e.statusCode()).log("Could not check the object storage bucket");
                return;
            }
            s3.createBucket(r -> r.bucket(bucket));
            log.atInfo().addKeyValue("bucket", bucket).log("Created object storage bucket");
        } catch (SdkException e) {
            log.atWarn().addKeyValue("bucket", bucket).addKeyValue("error", e.getMessage())
                    .log("Object storage unreachable at startup; uploads fail until it is back (bucket not checked)");
        }
    }

    private <T> T timed(String operation, Supplier<T> call) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            T result = call.get();
            outcome = "success";
            return result;
        } catch (SdkClientException e) {
            throw new StorageUnavailableException(e);   // network: unreachable, timed out
        } catch (S3Exception e) {
            if (e.statusCode() >= 500) {
                throw new StorageUnavailableException(e);
            }
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("storeforge.storage.requests", "operation", operation, "outcome", outcome));
        }
    }
}
