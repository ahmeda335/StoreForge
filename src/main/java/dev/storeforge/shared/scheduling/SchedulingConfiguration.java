package dev.storeforge.shared.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} background work (reservation sweeper, later outbox relay and jobs).
 * Integration tests switch it off and trigger the work explicitly, so test results never depend on
 * timer races.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "storeforge.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfiguration {
}
