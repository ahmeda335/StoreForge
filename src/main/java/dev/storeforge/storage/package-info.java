/**
 * Storage: object storage for files that don't belong in PostgreSQL (product images). Speaks the
 * plain S3 API (ADR-024), so the store behind it is replaceable.
 *
 * <p>Public API: {@link dev.storeforge.storage.ObjectStorage}. Keys are chosen by the owning module
 * and always start with {@code stores/{storeId}/}: a credential or URL for one key never reaches
 * another tenant's objects.
 */
@ApplicationModule(displayName = "Storage")
package dev.storeforge.storage;

import org.springframework.modulith.ApplicationModule;
