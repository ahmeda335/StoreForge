package dev.storeforge.identity.internal.application;

import dev.storeforge.identity.internal.security.AuthProperties;
import dev.storeforge.shared.crypto.SecretTokens;
import dev.storeforge.shared.email.EmailAddress;
import dev.storeforge.shared.ratelimit.DistributedRateLimiter;
import dev.storeforge.shared.ratelimit.DistributedRateLimiter.Limit;
import dev.storeforge.shared.ratelimit.RateLimitExceededException;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Two independent limits on every credential endpoint:
 * <ul>
 *   <li>per client IP: slows one machine trying many accounts (credential stuffing);</li>
 *   <li>per target email: slows many machines (a botnet) attacking one account.</li>
 * </ul>
 * Account lockout (see LoginService) is the third, slower layer.
 *
 * <p>Shared by all instances through Redis (P16): in memory, each API instance had its own budget,
 * so two instances behind the proxy allowed twice the attempts (EXP-024). Fails open to per-instance
 * buckets while Redis is down (ADR-020). Email keys are hashed: no addresses in Redis key names.
 */
@Component
class AuthRateLimits {

    private final DistributedRateLimiter limiter;
    private final Limit perIp;
    private final Limit perEmail;

    AuthRateLimits(AuthProperties properties, DistributedRateLimiter limiter) {
        this.limiter = limiter;
        this.perIp = new Limit("auth-ip", properties.rateLimits().perIp(), Duration.ofMinutes(1));
        this.perEmail = new Limit("auth-email", properties.rateLimits().perEmail(), Duration.ofMinutes(1));
    }

    void check(String action, String clientIp, EmailAddress email) {
        acquire(perIp, clientIp);
        acquire(perEmail, action + ":" + SecretTokens.hash(email.value()));
    }

    void checkIp(String clientIp) {
        acquire(perIp, clientIp);
    }

    private void acquire(Limit limit, String key) {
        var decision = limiter.tryConsume(limit, key);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(Duration.ofSeconds(decision.retryAfterSeconds()));
        }
    }
}
