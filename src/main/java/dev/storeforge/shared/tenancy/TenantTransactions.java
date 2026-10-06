package dev.storeforge.shared.tenancy;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a database transaction bound to ONE tenant. This is where row-level security gets
 * its input:
 *
 * <pre>
 *   BEGIN;
 *   SELECT set_config('app.current_store_id', '&lt;store&gt;', true);  -- true = LOCAL to this transaction
 *   ... queries: RLS policies only see rows of that store ...
 *   COMMIT;                                                         -- the setting disappears
 * </pre>
 *
 * Why transaction-LOCAL: connections are pooled. A session-level {@code SET} would stay on the
 * connection after it returns to the pool, and the NEXT request (maybe another tenant's) would
 * run with this tenant's context. {@code set_config(..., true)} is the parameterizable form of
 * {@code SET LOCAL}.
 *
 * <p>Nested calls for the same store join the outer transaction; switching to a different store
 * inside a transaction is a bug and fails loudly.
 */
@Component
public class TenantTransactions {

    public static final String MDC_KEY = "storeId";

    /** Span attribute: lets Tempo answer "show me the slow requests of store X". */
    public static final String SPAN_TAG = "storeforge.store_id";

    private final TransactionTemplate transaction;
    private final JdbcClient jdbc;
    private final Tracer tracer;

    TenantTransactions(TransactionTemplate transaction, JdbcClient jdbc, Tracer tracer) {
        this.transaction = transaction;
        this.jdbc = jdbc;
        this.tracer = tracer;
    }

    public <T> T inStore(StoreId storeId, Supplier<T> work) {
        String previousMdc = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, storeId.toString());
        // Store id on the current span (traces), NOT as a metric label: with thousands of stores it
        // would multiply every time series by the number of tenants (cardinality explosion).
        Span span = tracer.currentSpan();
        if (span != null) {
            span.tag(SPAN_TAG, storeId.toString());
        }
        try {
            return transaction.execute(status -> {
                bind(storeId);
                return work.get();
            });
        } finally {
            if (previousMdc == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousMdc);
            }
        }
    }

    public void runInStore(StoreId storeId, Runnable work) {
        inStore(storeId, () -> {
            work.run();
            return null;
        });
    }

    private void bind(StoreId storeId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Tenant context requires an active transaction");
        }
        // NULL if never set in this session, '' after a previous transaction-local setting ended.
        String current = jdbc.sql("SELECT coalesce(current_setting('app.current_store_id', true), '')")
                .query(String.class).single();
        if (!current.isEmpty()) {
            if (!current.equals(storeId.toString())) {
                throw new IllegalStateException("Transaction is already bound to another store");
            }
            return;
        }
        jdbc.sql("SELECT set_config('app.current_store_id', :storeId, true)")
                .param("storeId", storeId.toString())
                .query(String.class)
                .single();
    }
}
