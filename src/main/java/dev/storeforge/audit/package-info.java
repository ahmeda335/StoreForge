/**
 * Audit: an append-only record of who did what in which store.
 *
 * <p>Public API: {@link dev.storeforge.audit.AuditLog} and {@link dev.storeforge.audit.AuditEntry}.
 * Entries are written INSIDE the business transaction (an audited change and its audit row commit
 * or roll back together) and inside the tenant context: the RLS WITH CHECK policy makes it
 * impossible to write an entry for a store other than the one the transaction is bound to.
 */
@ApplicationModule(displayName = "Audit")
package dev.storeforge.audit;

import org.springframework.modulith.ApplicationModule;
