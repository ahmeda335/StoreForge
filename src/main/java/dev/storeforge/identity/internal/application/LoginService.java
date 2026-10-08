package dev.storeforge.identity.internal.application;

import dev.storeforge.identity.PasswordAuthenticator;
import dev.storeforge.identity.UserDirectory;
import dev.storeforge.identity.internal.domain.User;
import dev.storeforge.identity.internal.persistence.UserLoginCounters;
import dev.storeforge.identity.internal.persistence.UserRepository;
import dev.storeforge.identity.internal.security.AuthProperties;
import dev.storeforge.shared.crypto.SecretTokens;
import dev.storeforge.shared.email.EmailAddress;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password login.
 *
 * <p>Deliberately NOT {@code @Transactional}: a failed attempt must be RECORDED (lockout counter)
 * and then the request FAILS. In one transaction, the exception would roll back the counter and
 * lockout would never trigger. Each statement here commits on its own.
 *
 * <p>Every failure path (unknown email, wrong password, locked, disabled) returns the same
 * {@code invalid_credentials} answer and spends the same hashing time, so the endpoint does not
 * reveal which accounts exist or are locked. Only after a CORRECT password do we tell the user the
 * email is unverified.
 */
@Service
public class LoginService implements PasswordAuthenticator {

    /** Real Argon2 hash of a random string: verifying against it costs the same as a real check. */
    private final String dummyHash;

    private final UserRepository users;
    private final UserLoginCounters loginCounters;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final AuthRateLimits rateLimits;
    private final AuthProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    LoginService(UserRepository users, UserLoginCounters loginCounters, PasswordEncoder passwordEncoder,
                 TokenService tokens, AuthRateLimits rateLimits, AuthProperties properties,
                 MeterRegistry meterRegistry, Clock clock) {
        this.users = users;
        this.loginCounters = loginCounters;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.rateLimits = rateLimits;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(SecretTokens.generate());
    }

    public IssuedTokens login(String rawEmail, String password, String clientIp) {
        return tokens.issueNewSession(verify(rawEmail, password, clientIp));
    }

    @Override
    public UserDirectory.UserSummary authenticate(String rawEmail, String password, String clientIp) {
        User user = verify(rawEmail, password, clientIp);
        return new UserDirectory.UserSummary(user.id(), user.email().value(), user.emailVerified());
    }

    /** Every check of a password login; issuing tokens (or a browser session) is the caller's job. */
    private User verify(String rawEmail, String password, String clientIp) {
        EmailAddress email;
        try {
            email = EmailAddress.of(rawEmail);
        } catch (IllegalArgumentException e) {
            rateLimits.checkIp(clientIp);
            throw failure("invalid_credentials");
        }
        rateLimits.check("login", clientIp, email);
        Instant now = clock.instant();

        Optional<User> found = users.findByEmail(email.value());
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            throw failure("invalid_credentials");
        }
        User user = found.get();

        boolean locked = loginCounters.lockedUntil(user.id()).filter(until -> until.isAfter(now)).isPresent();
        boolean passwordMatches = passwordEncoder.matches(password, user.passwordHash());
        if (locked) {
            throw failure("locked");
        }
        if (!passwordMatches) {
            var lockout = properties.lockout();
            loginCounters.recordFailure(user.id(), now, lockout.threshold(), lockout.baseDuration(), lockout.maxDuration());
            throw failure("invalid_credentials");
        }
        if (!user.canAuthenticate()) {
            throw failure("disabled");
        }
        if (!user.emailVerified()) {
            meterRegistry.counter("storeforge.auth.logins", "outcome", "email_not_verified").increment();
            throw new IdentityExceptions.EmailNotVerified();
        }

        loginCounters.reset(user.id());
        if (passwordEncoder.upgradeEncoding(user.passwordHash())) {
            user.rehashPassword(passwordEncoder.encode(password), now);
            users.save(user);
        }
        meterRegistry.counter("storeforge.auth.logins", "outcome", "success").increment();
        return user;
    }

    /** The outcome is recorded precisely in metrics, but the client always gets the same answer. */
    private IdentityExceptions.InvalidCredentials failure(String outcome) {
        meterRegistry.counter("storeforge.auth.logins", "outcome", outcome).increment();
        return new IdentityExceptions.InvalidCredentials();
    }
}
