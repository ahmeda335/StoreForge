package dev.storeforge.identity.internal.platform;

import dev.storeforge.audit.PlatformAuditLog;
import dev.storeforge.identity.internal.domain.User;
import dev.storeforge.identity.internal.persistence.UserRepository;
import dev.storeforge.identity.internal.security.PlatformTokens;
import dev.storeforge.shared.crypto.SecretBox;
import dev.storeforge.shared.crypto.SecretTokens;
import dev.storeforge.shared.ratelimit.DistributedRateLimiter;
import dev.storeforge.shared.ratelimit.DistributedRateLimiter.Limit;
import dev.storeforge.shared.ratelimit.RateLimitExceededException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Second factor for platform administrators (ADR-026): TOTP, mandatory, plus one-time recovery codes.
 * <ol>
 *   <li>{@link #enroll}: a new secret (sealed with AES-GCM), shown once as an otpauth URI.</li>
 *   <li>{@link #confirm}: the first valid code proves the app has it; 10 recovery codes are shown
 *       once and stored as SHA-256 hashes.</li>
 *   <li>{@link #startSession}: password session + a code → a short-lived PLATFORM token. Each time
 *       step is accepted once (conditional update); wrong codes are limited per admin.</li>
 * </ol>
 */
@Service
@Slf4j
public class PlatformMfaService {

    static final int RECOVERY_CODES = 10;
    private static final Limit ATTEMPTS = new Limit("platform-mfa", 5, Duration.ofMinutes(15));

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final UserRepository users;
    private final PlatformTokens tokens;
    private final PlatformAuditLog audit;
    private final DistributedRateLimiter limiter;
    private final PlatformProperties properties;
    private final SecretBox secrets;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PlatformMfaService(JdbcClient jdbc, TransactionTemplate transaction, UserRepository users, PlatformTokens tokens,
                              PlatformAuditLog audit, DistributedRateLimiter limiter, PlatformProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.users = users;
        this.tokens = tokens;
        this.audit = audit;
        this.limiter = limiter;
        this.properties = properties;
        this.secrets = new SecretBox(Base64.getDecoder().decode(properties.mfaEncryptionKey()));
        this.clock = clock;
    }

    public record Enrollment(String secret, String otpauthUri) {
        @Override
        public String toString() {
            return "Enrollment[REDACTED]";
        }
    }

    public record Session(String accessToken, long expiresInSeconds, List<String> amr) {
        @Override
        public String toString() {
            return "Session[REDACTED]";
        }
    }

    private record Admin(@Nullable String sealedSecret, @Nullable Instant confirmedAt) {
    }

    public Enrollment enroll(UUID userId, @Nullable String clientIp) {
        return transaction.execute(status -> {
            Admin admin = lock(userId);
            if (admin.confirmedAt() != null) {
                throw new PlatformExceptions.AlreadyEnrolled();
            }
            byte[] secret = Totp.newSecret(random);
            jdbc.sql("UPDATE platform_admins SET totp_secret = :secret WHERE user_id = :user")
                    .param("secret", secrets.seal(Base32.encode(secret), userId.toString())).param("user", userId).update();
            audit.record(userId, "platform.mfa_enrollment_started", "user", userId.toString(), Map.of(), clientIp);
            User user = users.findById(userId).orElseThrow(PlatformExceptions.NotAPlatformAdmin::new);
            return new Enrollment(Base32.encode(secret), Totp.otpauthUri(properties.totpIssuer(), user.email().value(), secret));
        });
    }

    /** @return the recovery codes, shown ONCE */
    public List<String> confirm(UUID userId, String code, @Nullable String clientIp) {
        return limitingFailures(userId, () -> transaction.execute(status -> {
            Admin admin = lock(userId);
            if (admin.confirmedAt() != null) {
                throw new PlatformExceptions.AlreadyEnrolled();
            }
            if (admin.sealedSecret() == null) {
                throw new PlatformExceptions.NotEnrolled();
            }
            long step = verify(userId, admin, code).orElseThrow(PlatformExceptions.InvalidCode::new);
            List<String> codes = new ArrayList<>();
            List<String> hashes = new ArrayList<>();
            for (int i = 0; i < RECOVERY_CODES; i++) {
                String recovery = recoveryCode();
                codes.add(recovery);
                hashes.add(SecretTokens.hash(normalize(recovery)));
            }
            jdbc.sql("""
                    UPDATE platform_admins SET totp_confirmed_at = :now, totp_last_step = :step, recovery_code_hashes = :hashes
                     WHERE user_id = :user
                    """)
                    .param("now", Timestamp.from(clock.instant())).param("step", step).param("hashes", hashes.toArray(String[]::new))
                    .param("user", userId).update();
            audit.record(userId, "platform.mfa_enrolled", "user", userId.toString(), Map.of(), clientIp);
            return codes;
        }));
    }

    /** Exactly one of {@code code} / {@code recoveryCode}. */
    public Session startSession(UUID userId, @Nullable String code, @Nullable String recoveryCode, @Nullable String clientIp) {
        return limitingFailures(userId, () -> transaction.execute(status -> {
            Admin admin = lock(userId);
            if (admin.confirmedAt() == null) {
                throw new PlatformExceptions.NotEnrolled();
            }
            List<String> amr;
            if (recoveryCode != null) {
                int used = jdbc.sql("""
                                UPDATE platform_admins SET recovery_code_hashes = array_remove(recovery_code_hashes, :hash)
                                 WHERE user_id = :user AND :hash = ANY (recovery_code_hashes)
                                """)
                        .param("hash", SecretTokens.hash(normalize(recoveryCode))).param("user", userId).update();
                if (used == 0) {
                    throw new PlatformExceptions.InvalidCode();
                }
                amr = List.of("pwd", "recovery_code");
                log.atWarn().addKeyValue("userId", userId).log("Platform admin used a recovery code");
            } else {
                long step = verify(userId, admin, code).orElseThrow(PlatformExceptions.InvalidCode::new);
                // A step is accepted ONCE: a code seen by a shoulder-surfer or a proxy log can't be replayed.
                int accepted = jdbc.sql("""
                                UPDATE platform_admins SET totp_last_step = :step
                                 WHERE user_id = :user AND (totp_last_step IS NULL OR totp_last_step < :step)
                                """)
                        .param("step", step).param("user", userId).update();
                if (accepted == 0) {
                    throw new PlatformExceptions.InvalidCode();
                }
                amr = List.of("pwd", "otp");
            }
            User user = users.findById(userId).orElseThrow(PlatformExceptions.NotAPlatformAdmin::new);
            var token = tokens.issue(userId, user.securityStamp(), amr, properties.sessionTtl());
            audit.record(userId, "platform.session_started", "user", userId.toString(), Map.of("amr", amr), clientIp);
            return new Session(token.value(), token.expiresInSeconds(), amr);
        }));
    }

    /** Used by the platform token check: still an admin, with a confirmed second factor? */
    public boolean isEnrolledAdmin(UUID userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform_admins WHERE user_id = :user AND totp_confirmed_at IS NOT NULL)")
                .param("user", userId).query(Boolean.class).single();
    }

    private Admin lock(UUID userId) {
        return jdbc.sql("SELECT totp_secret, totp_confirmed_at FROM platform_admins WHERE user_id = :user FOR UPDATE")
                .param("user", userId)
                .query((rs, n) -> new Admin(rs.getString("totp_secret"),
                        rs.getTimestamp("totp_confirmed_at") == null ? null : rs.getTimestamp("totp_confirmed_at").toInstant()))
                .optional().orElseThrow(PlatformExceptions.NotAPlatformAdmin::new);
    }

    private OptionalLong verify(UUID userId, Admin admin, @Nullable String code) {
        if (admin.sealedSecret() == null || code == null) {
            return OptionalLong.empty();
        }
        byte[] secret = Base32.decode(secrets.open(admin.sealedSecret(), userId.toString()));
        return Totp.verify(secret, code.trim(), clock.instant());
    }

    /**
     * Only WRONG codes count against the 5 attempts per 15 minutes. A token is taken before checking
     * (parallel guesses can't all slip past) and given back unless the outcome was an invalid code.
     */
    private <T> T limitingFailures(UUID userId, java.util.function.Supplier<T> attempt) {
        var decision = limiter.tryConsume(ATTEMPTS, userId.toString());
        if (!decision.allowed()) {
            throw new RateLimitExceededException(Duration.ofSeconds(decision.retryAfterSeconds()));
        }
        boolean wrongCode = false;
        try {
            return attempt.get();
        } catch (PlatformExceptions.InvalidCode e) {
            wrongCode = true;
            throw e;
        } finally {
            if (!wrongCode) {
                limiter.refund(ATTEMPTS, userId.toString());
            }
        }
    }

    /** 10 characters from an unambiguous alphabet (no 0/O, 1/I/L), shown as xxxxx-xxxxx: ~50 bits. */
    private String recoveryCode() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                code.append('-');
            }
            code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return code.toString();
    }

    private static String normalize(String recoveryCode) {
        return recoveryCode.trim().toLowerCase(java.util.Locale.ROOT).replace("-", "");
    }
}
