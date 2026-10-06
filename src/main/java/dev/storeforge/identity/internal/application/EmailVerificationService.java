package dev.storeforge.identity.internal.application;

import dev.storeforge.identity.internal.domain.EmailAddress;
import dev.storeforge.identity.internal.persistence.EmailTokenStore;
import dev.storeforge.identity.internal.persistence.EmailTokenStore.Purpose;
import dev.storeforge.identity.internal.persistence.UserRepository;
import dev.storeforge.identity.internal.security.AuthProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {

    private final UserRepository users;
    private final EmailTokenStore emailTokens;
    private final AuthRateLimits rateLimits;
    private final ApplicationEventPublisher events;
    private final AuthProperties properties;
    private final Clock clock;

    EmailVerificationService(UserRepository users, EmailTokenStore emailTokens, AuthRateLimits rateLimits,
                             ApplicationEventPublisher events, AuthProperties properties, Clock clock) {
        this.users = users;
        this.emailTokens = emailTokens;
        this.rateLimits = rateLimits;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** Consuming the token and marking the email verified succeed or fail together. */
    @Transactional
    public void confirm(String rawToken, String clientIp) {
        rateLimits.checkIp(clientIp);
        Instant now = clock.instant();
        var userId = emailTokens.consume(SecretTokens.hash(rawToken), Purpose.VERIFY_EMAIL, now)
                .orElseThrow(IdentityExceptions.InvalidEmailToken::new);
        users.findById(userId).orElseThrow(IdentityExceptions.InvalidEmailToken::new).verifyEmail(now);
    }

    /** Same response whether or not the account exists or is already verified (enumeration). */
    @Transactional
    public void resend(String rawEmail, String clientIp) {
        EmailAddress email = PasswordPolicy.parseEmail(rawEmail);
        rateLimits.check("verify-resend", clientIp, email);
        Instant now = clock.instant();
        users.findByEmail(email.value()).filter(user -> !user.emailVerified()).ifPresent(user -> {
            String token = SecretTokens.generate();
            emailTokens.replace(user.id(), Purpose.VERIFY_EMAIL, SecretTokens.hash(token),
                    now.plus(properties.emailVerificationTtl()), now);
            events.publishEvent(new IdentityEmails.VerifyEmail(email, token));
        });
    }
}
