package dev.storeforge.identity.internal.application;

import dev.storeforge.identity.internal.domain.User;
import dev.storeforge.identity.internal.persistence.RefreshTokenStore;
import dev.storeforge.identity.internal.persistence.RefreshTokenStore.ConsumedToken;
import dev.storeforge.identity.internal.persistence.UserRepository;
import dev.storeforge.identity.internal.security.AccessTokenIssuer;
import dev.storeforge.identity.internal.security.AuthProperties;
import dev.storeforge.shared.ids.Ids;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh-token lifecycle: issue, rotate (with reuse detection), revoke.
 *
 * <p>Transaction boundaries are explicit (TransactionTemplate) because they differ per branch:
 * rotation (consume old + insert new) must be atomic, but revoking a family after detecting reuse
 * must COMMIT even though the request then fails with 401. With a method-level
 * {@code @Transactional}, throwing the 401 would roll the revocation back.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final RefreshTokenStore refreshTokens;
    private final UserRepository users;
    private final AccessTokenIssuer accessTokens;
    private final AuthProperties properties;
    private final TransactionTemplate transaction;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    TokenService(RefreshTokenStore refreshTokens, UserRepository users, AccessTokenIssuer accessTokens,
                 AuthProperties properties, TransactionTemplate transaction, MeterRegistry meterRegistry, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.transaction = transaction;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** New login: a new refresh-token family. */
    IssuedTokens issueNewSession(User user) {
        String refreshToken = storeNewRefreshToken(user.id(), Ids.newId(), clock.instant());
        return new IssuedTokens(accessTokens.issue(user.id(), user.securityStamp()), refreshToken);
    }

    public IssuedTokens refresh(String rawRefreshToken) {
        String hash = SecretTokens.hash(rawRefreshToken);
        Instant now = clock.instant();

        Optional<IssuedTokens> rotated = transaction.execute(status -> refreshTokens.consume(hash, now)
                .flatMap(consumed -> rotate(consumed, now)));
        if (rotated != null && rotated.isPresent()) {
            count("rotated");
            return rotated.get();
        }

        refreshTokens.find(hash).filter(token -> token.used() && !token.revoked()).ifPresentOrElse(reused -> {
            // An already-rotated token was presented again: either an attacker replays a stolen token,
            // or the legitimate client (after the attacker already rotated). We can't tell which, so
            // the whole session family dies and the user must sign in again.
            int revoked = refreshTokens.revokeFamily(reused.familyId(), now);
            count("reuse_detected");
            log.atWarn().addKeyValue("userId", reused.userId()).addKeyValue("familyId", reused.familyId())
                    .addKeyValue("revokedTokens", revoked)
                    .log("Refresh token reuse detected; session family revoked");
        }, () -> count("invalid"));
        throw new IdentityExceptions.InvalidRefreshToken();
    }

    private Optional<IssuedTokens> rotate(ConsumedToken consumed, Instant now) {
        return users.findById(consumed.userId())
                .filter(User::canAuthenticate)
                .map(user -> new IssuedTokens(
                        accessTokens.issue(user.id(), user.securityStamp()),
                        storeNewRefreshToken(user.id(), consumed.familyId(), now)));
    }

    /** Logout: revoke the whole family of the presented token. Idempotent; unknown tokens are ignored. */
    public void logout(String rawRefreshToken) {
        refreshTokens.find(SecretTokens.hash(rawRefreshToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.familyId(), clock.instant()));
    }

    void revokeAllSessions(UUID userId, Instant now) {
        refreshTokens.revokeAllForUser(userId, now);
    }

    private String storeNewRefreshToken(UUID userId, UUID familyId, Instant now) {
        String raw = SecretTokens.generate();
        refreshTokens.insert(userId, familyId, SecretTokens.hash(raw), now.plus(properties.refreshTokenTtl()), now);
        return raw;
    }

    private void count(String outcome) {
        meterRegistry.counter("storeforge.auth.token.refreshes", "outcome", outcome).increment();
    }
}
