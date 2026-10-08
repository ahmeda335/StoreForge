/**
 * Outbound webhooks: merchants subscribe URLs to public topics ({@code orders/create},
 * {@code inventory_levels/update}, ...); internal outbox events are mapped to those topics, signed
 * and delivered with retries, history, replay, secret rotation, per-endpoint pausing and an SSRF
 * guard. No public Java API: other modules only publish events.
 */
@ApplicationModule(displayName = "Webhooks")
package dev.storeforge.webhooks;

import org.springframework.modulith.ApplicationModule;
