package dev.storeforge.stores;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the {@link StoreInfo} of the storefront being requested, resolved from the request
 * HOSTNAME ({@code acme.storeforge.localhost}). Unknown or inactive stores → 404 before the handler
 * runs. The store is never taken from a path or body parameter on storefront APIs.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentStorefront {
}
