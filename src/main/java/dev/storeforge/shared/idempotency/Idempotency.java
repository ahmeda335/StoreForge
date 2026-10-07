package dev.storeforge.shared.idempotency;

import dev.storeforge.shared.crypto.SecretTokens;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes a request safe to retry: the same Idempotency-Key always yields the same response, and the
 * work runs at most once (blueprint 08, ADR-013).
 *
 * <p>Everything happens in ONE tenant transaction:
 * <ol>
 *   <li>{@code INSERT} a claim row for (store, scope, key). A concurrent duplicate's INSERT
 *       <b>blocks</b> on the primary-key index until this transaction ends;</li>
 *   <li>run the work (which joins the same transaction);</li>
 *   <li>store the response in the claim row; COMMIT.</li>
 * </ol>
 * If the work fails, the transaction rolls back, the claim disappears and a retry runs the work
 * again (failures here are deterministic validation errors, so re-running them is harmless). The
 * duplicate that was waiting then sees either the committed response (→ replay) or no row (→ it
 * runs the work itself). There is never a window in which both run it.
 *
 * <p>Limit of this design: the transaction (and the duplicates' wait) lasts as long as the work.
 * That is fine while the work is local SQL. When the work includes slow external calls (payments,
 * P10), an explicit IN_PROGRESS state committed up front is needed instead.
 */
@Component
@RequiredArgsConstructor
public class Idempotency {

    private static final Pattern KEY_FORMAT = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private final JdbcClient jdbc;
    private final TenantTransactions tenant;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** The response to send: either produced now, or replayed from the first request with this key. */
    public record Result(int status, String jsonBody, boolean replayed) {
    }

    /**
     * @param key            client-supplied Idempotency-Key header (null/invalid → 400)
     * @param requestPayload what identifies the request's meaning (method, path, body); hashed and
     *                       compared on replay so a key can't be reused for a different request
     */
    public <T> Result execute(StoreId storeId, String scope, @Nullable String key, String requestPayload,
                              int successStatus, Supplier<T> work) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw new IdempotencyExceptions.KeyRequired();
        }
        String requestHash = SecretTokens.hash(requestPayload);
        return tenant.inStore(storeId, () -> {
            int claimed = jdbc.sql("""
                    INSERT INTO idempotency_keys (store_id, scope, idempotency_key, request_hash, response_status, response_body, created_at)
                    VALUES (:storeId, :scope, :key, :hash, 0, '', :now)
                    ON CONFLICT (store_id, scope, idempotency_key) DO NOTHING
                    """)
                    .param("storeId", storeId.value())
                    .param("scope", scope)
                    .param("key", key)
                    .param("hash", requestHash)
                    .param("now", Timestamp.from(clock.instant()))
                    .update();
            if (claimed == 0) {
                return replay(storeId, scope, key, requestHash);
            }
            String body = jsonMapper.writeValueAsString(work.get());
            jdbc.sql("""
                    UPDATE idempotency_keys SET response_status = :status, response_body = :body
                     WHERE store_id = :storeId AND scope = :scope AND idempotency_key = :key
                    """)
                    .param("status", successStatus)
                    .param("body", body)
                    .param("storeId", storeId.value())
                    .param("scope", scope)
                    .param("key", key)
                    .update();
            return new Result(successStatus, body, false);
        });
    }

    private Result replay(StoreId storeId, String scope, String key, String requestHash) {
        record Stored(String requestHash, int status, String body) {
        }
        Stored stored = jdbc.sql("""
                SELECT request_hash, response_status, response_body FROM idempotency_keys
                 WHERE store_id = :storeId AND scope = :scope AND idempotency_key = :key
                """)
                .param("storeId", storeId.value())
                .param("scope", scope)
                .param("key", key)
                .query((rs, rowNum) -> new Stored(rs.getString("request_hash"), rs.getInt("response_status"), rs.getString("response_body")))
                .single();
        if (!stored.requestHash().equals(requestHash)) {
            throw new IdempotencyExceptions.KeyReused();
        }
        meterRegistry.counter("storeforge.idempotency.replays", "scope", scope).increment();
        return new Result(stored.status(), stored.body(), true);
    }
}
