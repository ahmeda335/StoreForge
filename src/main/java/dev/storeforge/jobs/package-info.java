/**
 * Jobs: reliable asynchronous work on PostgreSQL (ADR-005, ADR-016).
 *
 * <ul>
 *   <li>{@link dev.storeforge.jobs.Outbox}: record a domain event in the CURRENT transaction;</li>
 *   <li>{@link dev.storeforge.jobs.EventHandler}: consume events (at-least-once, so be idempotent);</li>
 *   <li>{@link dev.storeforge.jobs.Jobs} + {@link dev.storeforge.jobs.JobHandler}: enqueue/run a job directly;</li>
 *   <li>{@link dev.storeforge.jobs.Inbox}: make a consumer's database effect happen once per message.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Jobs & Outbox")
package dev.storeforge.jobs;

import org.springframework.modulith.ApplicationModule;
