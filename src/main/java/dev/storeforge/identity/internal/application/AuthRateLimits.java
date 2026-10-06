package dev.storeforge.identity.internal.application;

import dev.storeforge.identity.internal.domain.EmailAddress;
import dev.storeforge.identity.internal.security.AuthProperties;
import dev.storeforge.shared.ratelimit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Two independent limits on every credential endpoint:
 * <ul>
 *   <li>per client IP: slows one machine trying many accounts (credential stuffing);</li>
 *   <li>per target email: slows many machines (a botnet) attacking one account.</li>
 * </ul>
 * Account lockout (see LoginService) is the third, slower layer.
 */
@Component
class AuthRateLimits {

    private final RateLimiter perIp;
    private final RateLimiter perEmail;

    AuthRateLimits(AuthProperties properties, MeterRegistry meterRegistry) {
        this.perIp = new RateLimiter("auth-ip", properties.rateLimits().perIp(), Duration.ofMinutes(1), meterRegistry);
        this.perEmail = new RateLimiter("auth-email", properties.rateLimits().perEmail(), Duration.ofMinutes(1), meterRegistry);
    }

    void check(String action, String clientIp, EmailAddress email) {
        perIp.acquire(clientIp);
        perEmail.acquire(action + ":" + email.value());
    }

    void checkIp(String clientIp) {
        perIp.acquire(clientIp);
    }
}
