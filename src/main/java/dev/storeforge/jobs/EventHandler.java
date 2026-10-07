package dev.storeforge.jobs;

import java.util.Set;

/**
 * A consumer of outbox events. Delivery is AT LEAST ONCE: the same event can be handled twice
 * (worker crash after the effect, lease expiry...). Implementations must be idempotent, e.g. with
 * the {@link Inbox}, a natural key (UNIQUE dedup key), or a state check.
 *
 * <p>Runs outside any transaction: open a tenant transaction ({@code TenantTransactions.inStore})
 * for tenant data. Throwing retries with backoff; {@link PermanentJobFailure} gives up immediately.
 */
public interface EventHandler {

    /** Stable consumer name; part of the job type ({@code event:<name>}) and of inbox keys. Never rename casually. */
    String name();

    Set<String> eventTypes();

    void handle(Event event);
}
