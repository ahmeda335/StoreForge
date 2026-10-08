/**
 * Developer platform: credentials for third-party code (API keys; app installations in P12b),
 * scopes, the versioned developer API's cross-cutting rules (authentication, store binding, scope
 * checks, rate limits, usage), and its OpenAPI document.
 *
 * <p>Public API, used by modules that expose developer endpoints: {@link dev.storeforge.developer.Scope},
 * {@link dev.storeforge.developer.RequiresScope}, {@link dev.storeforge.developer.ApiClientPrincipal},
 * {@link dev.storeforge.developer.DeveloperApi}, {@link dev.storeforge.developer.ApiCredentialRevoked}.
 */
@ApplicationModule(displayName = "Developer platform")
package dev.storeforge.developer;

import org.springframework.modulith.ApplicationModule;
