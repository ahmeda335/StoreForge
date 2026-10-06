package dev.storeforge.identity.internal.application;

import dev.storeforge.identity.internal.domain.EmailAddress;
import dev.storeforge.identity.internal.domain.User;
import dev.storeforge.identity.internal.persistence.EmailTokenStore;
import dev.storeforge.identity.internal.persistence.EmailTokenStore.Purpose;
import dev.storeforge.identity.internal.persistence.UserLoginCounters;
import dev.storeforge.identity.internal.persistence.UserRepository;
import dev.storeforge.identity.internal.security.AuthProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {

    private final UserRepository users;
    private final EmailTokenStore emailTokens;
    private final UserLoginCounters loginCounters;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimits rateLimits;
    private final ApplicationEventPublisher events;
    private final AuthProperties properties;
    private final Clock clock;

    PasswordResetService(UserRepository users, EmailTokenStore emailTokens, UserLoginCounters loginCounters,
                         TokenService tokens, PasswordEncoder passwordEncoder, AuthRateLimits rateLimits,
                         ApplicationEventPublisher events, AuthProperties properties, Clock clock) {
        this.users = users;
        this.emailTokens = emailTokens;
        this.loginCounters = loginCounters;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.rateLimits = rateLimits;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** Same response whether or not the account exists (enumeration). */
    @Transactional
    public void request(String rawEmail, String clientIp) {
        EmailAddress email = PasswordPolicy.parseEmail(rawEmail);
        rateLimits.check("password-reset", clientIp, email);
        Instant now = clock.instant();
        users.findByEmail(email.value()).filter(User::canAuthenticate).ifPresent(user -> {
            String token = SecretTokens.generate();
            emailTokens.replace(user.id(), Purpose.RESET_PASSWORD, SecretTokens.hash(token),
                    now.plus(properties.passwordResetTtl()), now);
            events.publishEvent(new IdentityEmails.ResetPassword(email, token));
        });
    }

    /**
     * One transaction: consume the token, set the password, bump the security stamp (kills all access
     * tokens), revoke all refresh tokens (kills all sessions), clear any lockout. Whoever had the old
     * password, e.g. an attacker, is fully logged out. Following an emailed link also proves
     * ownership of the address, so the email counts as verified.
     */
    @Transactional
    public void confirm(String rawToken, String newPassword, String clientIp) {
        rateLimits.checkIp(clientIp);
        Instant now = clock.instant();
        var userId = emailTokens.consume(SecretTokens.hash(rawToken), Purpose.RESET_PASSWORD, now)
                .orElseThrow(IdentityExceptions.InvalidEmailToken::new);
        User user = users.findById(userId).orElseThrow(IdentityExceptions.InvalidEmailToken::new);
        PasswordPolicy.check(newPassword, user.email());

        user.changePassword(passwordEncoder.encode(newPassword), now);
        user.verifyEmail(now);
        tokens.revokeAllSessions(user.id(), now);
        loginCounters.reset(user.id());
    }
}
