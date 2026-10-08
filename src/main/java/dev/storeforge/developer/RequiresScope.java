package dev.storeforge.developer;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mandatory on EVERY developer API endpoint ({@code /api/**}): the scopes the credential needs.
 * An empty list means "any valid credential; the endpoint checks finer-grained rules itself" and
 * must be justified in the endpoint's documentation. An architecture test fails the build for any
 * {@code /api/**} handler without this annotation, so "forgot to protect it" can't ship.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresScope {

    Scope[] value();
}
