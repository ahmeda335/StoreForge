/**
 * Identity: platform user accounts (merchants and staff), credentials and tokens.
 *
 * <p>Public API: {@link dev.storeforge.identity.UserPrincipal}, the authenticated caller that other
 * modules receive in their controllers. Everything else is internal.
 *
 * <p>Customers are NOT users: they are store-scoped and live in the customers module (P7).
 */
@ApplicationModule(displayName = "Identity")
package dev.storeforge.identity;

import org.springframework.modulith.ApplicationModule;
