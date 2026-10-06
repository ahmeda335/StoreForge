package dev.storeforge.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time is an input: code that depends on "now" (token expiry, lockouts, reservations) takes a
 * {@link Clock} so tests can control it instead of sleeping.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
