package dev.storeforge.catalog.internal.domain;

/** DRAFT: being prepared, invisible to customers. ACTIVE: sellable. ARCHIVED: kept for history (orders reference it). */
public enum ProductStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
