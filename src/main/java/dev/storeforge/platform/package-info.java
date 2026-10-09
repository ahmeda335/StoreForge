/**
 * Platform: the API for StoreForge staff ({@code /platform/v1}): stores (search, suspend, reinstate),
 * the job queue, the platform audit log. Authentication (password + mandatory TOTP, platform tokens)
 * belongs to identity; every action here is audited (ADR-026).
 */
@ApplicationModule(displayName = "Platform")
package dev.storeforge.platform;

import org.springframework.modulith.ApplicationModule;
