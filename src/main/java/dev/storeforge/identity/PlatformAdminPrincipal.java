package dev.storeforge.identity;

import java.util.List;
import java.util.UUID;

/**
 * A request authenticated with a PLATFORM access token: a StoreForge staff member who passed a
 * second factor in the last few minutes. {@code amr} says how (RFC 8176 style: {@code pwd} plus
 * {@code otp} or {@code recovery_code}).
 */
public record PlatformAdminPrincipal(UUID userId, List<String> amr) {
}
