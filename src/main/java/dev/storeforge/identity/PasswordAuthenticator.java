package dev.storeforge.identity;

/**
 * Checks a user's email and password with ALL the protections of the login API: per-IP and per-email
 * rate limits, lockout after repeated failures, constant-time behaviour for unknown accounts, and
 * verified-email requirement. For other login surfaces (the OAuth authorization page), so that a
 * second login door is never weaker than the first.
 *
 * @throws dev.storeforge.shared.error.StoreForgeException invalid credentials, unverified email,
 *         or rate limited (the same answers the login API gives)
 */
public interface PasswordAuthenticator {

    UserDirectory.UserSummary authenticate(String email, String password, String clientIp);
}
