package dev.storeforge.identity.internal.persistence;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Failed-login bookkeeping with single atomic statements.
 *
 * <p>Concurrent failed logins for one account are exactly what a brute-force attack produces. A
 * read-modify-write ("load user, count + 1, save") would lose increments under concurrency (two
 * requests read 4, both write 5), which lets an attacker exceed the lockout threshold. The
 * {@code SET failed_login_count = failed_login_count + 1} below is evaluated by PostgreSQL under a
 * row lock, so every failure is counted.
 */
@Repository
public class UserLoginCounters {

    private final JdbcClient jdbc;

    UserLoginCounters(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Instant> lockedUntil(UUID userId) {
        return jdbc.sql("SELECT locked_until FROM users WHERE id = :id AND locked_until IS NOT NULL")
                .param("id", userId)
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    /**
     * Counts a failure. From the {@code threshold}-th consecutive failure on, the account is locked
     * for {@code baseLock * 2^(failures - threshold)}, capped at {@code maxLock}. Locks are temporary
     * on purpose: permanent lockout would let anyone lock a victim out by guessing wrong passwords.
     */
    public void recordFailure(UUID userId, Instant now, int threshold, Duration baseLock, Duration maxLock) {
        jdbc.sql("""
                UPDATE users
                   SET failed_login_count = failed_login_count + 1,
                       locked_until = CASE
                           WHEN failed_login_count + 1 >= :threshold
                           THEN CAST(:now AS timestamptz) + make_interval(secs => LEAST(
                                    :baseSeconds * power(2, failed_login_count + 1 - :threshold),
                                    :maxSeconds))
                           ELSE locked_until
                       END
                 WHERE id = :id
                """)
                .param("id", userId)
                .param("now", Timestamp.from(now))
                .param("threshold", threshold)
                .param("baseSeconds", baseLock.toSeconds())
                .param("maxSeconds", maxLock.toSeconds())
                .update();
    }

    public void reset(UUID userId) {
        jdbc.sql("""
                UPDATE users SET failed_login_count = 0, locked_until = NULL
                 WHERE id = :id AND (failed_login_count <> 0 OR locked_until IS NOT NULL)
                """)
                .param("id", userId)
                .update();
    }
}
