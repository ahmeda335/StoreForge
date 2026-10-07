package dev.storeforge.identity.internal.domain;

import dev.storeforge.shared.email.EmailAddress;
import dev.storeforge.shared.ids.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A platform user account (merchant owner or staff).
 *
 * <p>{@code @DynamicUpdate}: UPDATE statements contain only changed columns. The login-failure
 * counters are incremented with atomic SQL outside this entity (see {@code UserLoginCounters});
 * a full-row UPDATE from a stale entity would silently overwrite them (a lost update).
 *
 * <p>{@code securityStamp} is copied into every access token. Incrementing it ("log out
 * everywhere", password reset) invalidates all access tokens issued before, without a token
 * blacklist and without the one-second precision problems of comparing JWT {@code iat} timestamps.
 */
@Entity
@Table(name = "users")
@DynamicUpdate
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @Getter
    private UUID id;

    @Column(columnDefinition = "citext", nullable = false)
    private String email;

    @Column(nullable = false)
    @Getter
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    private Instant emailVerifiedAt;

    @Column(nullable = false)
    @Getter
    private int securityStamp;

    @Column(nullable = false)
    @Getter
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    private User(EmailAddress email, String passwordHash, Instant now) {
        this.id = Ids.newId();
        this.email = email.value();
        this.passwordHash = passwordHash;
        this.status = UserStatus.ACTIVE;
        this.securityStamp = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static User register(EmailAddress email, String passwordHash, Instant now) {
        return new User(email, passwordHash, now);
    }

    public void verifyEmail(Instant now) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
            updatedAt = now;
        }
    }

    /** New password; every previously issued access token becomes invalid. */
    public void changePassword(String newPasswordHash, Instant now) {
        passwordHash = newPasswordHash;
        securityStamp++;
        updatedAt = now;
    }

    /** Transparent re-hash after the hashing algorithm/parameters were upgraded. */
    public void rehashPassword(String newPasswordHash, Instant now) {
        passwordHash = newPasswordHash;
        updatedAt = now;
    }

    public boolean canAuthenticate() {
        return status == UserStatus.ACTIVE;
    }

    public EmailAddress email() {
        return EmailAddress.of(email);
    }

    public boolean emailVerified() {
        return emailVerifiedAt != null;
    }

    @Override
    public String toString() {
        return "User[id=" + id + "]";   // never print email/password hash into logs
    }
}
