package dev.storeforge.identity.internal.application;

import dev.storeforge.identity.internal.domain.User;
import dev.storeforge.identity.internal.persistence.EmailTokenStore;
import dev.storeforge.identity.internal.persistence.EmailTokenStore.Purpose;
import dev.storeforge.identity.internal.persistence.UserRepository;
import dev.storeforge.identity.internal.security.AuthProperties;
import dev.storeforge.shared.crypto.SecretTokens;
import dev.storeforge.shared.email.EmailAddress;
import dev.storeforge.shared.error.ConstraintViolations;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration with ACCOUNT-ENUMERATION resistance: the HTTP response is identical whether or not
 * the email is already registered (the existing owner gets an email instead). Otherwise the signup
 * form would tell an attacker which addresses have accounts, the first step of targeted attacks.
 *
 * <p>The password is hashed in both branches so response timing does not leak the answer either.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final UserRepository users;
    private final EmailTokenStore emailTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimits rateLimits;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final AuthProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public void register(String rawEmail, String password, String clientIp) {
        EmailAddress email = PasswordPolicy.parseEmail(rawEmail);
        rateLimits.check("register", clientIp, email);
        PasswordPolicy.check(password, email);
        String passwordHash = passwordEncoder.encode(password);
        Instant now = clock.instant();

        try {
            transaction.executeWithoutResult(status -> {
                if (users.findByEmail(email.value()).isPresent()) {
                    events.publishEvent(new IdentityEmails.AccountAlreadyExists(email));
                    return;
                }
                // saveAndFlush: the INSERT must hit the database now, so a unique violation surfaces
                // here (inside the try) rather than at commit time.
                User user = users.saveAndFlush(User.register(email, passwordHash, now));
                String token = SecretTokens.generate();
                emailTokens.replace(user.id(), Purpose.VERIFY_EMAIL, SecretTokens.hash(token),
                        now.plus(properties.emailVerificationTtl()), now);
                events.publishEvent(new IdentityEmails.VerifyEmail(email, token));
                meterRegistry.counter("storeforge.auth.registrations").increment();
            });
        } catch (DataIntegrityViolationException e) {
            // Two concurrent registrations for the same email: the check above saw nothing for both,
            // and the UNIQUE constraint decided the winner. The loser is just "already exists".
            if (!ConstraintViolations.isUniqueViolation(e, "uq_users_email")) {
                throw e;
            }
            events.publishEvent(new IdentityEmails.AccountAlreadyExists(email));
        }
    }
}
