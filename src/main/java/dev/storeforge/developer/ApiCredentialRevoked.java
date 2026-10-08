package dev.storeforge.developer;

import dev.storeforge.shared.tenancy.StoreId;
import java.util.UUID;

/**
 * Published (synchronously, inside the revoking tenant transaction) when an API key is revoked or an
 * app is uninstalled. Modules that own credential-scoped data (webhook subscriptions) remove it in
 * the same transaction: revocation and cleanup commit together.
 */
public record ApiCredentialRevoked(StoreId storeId, UUID credentialId) {
}
