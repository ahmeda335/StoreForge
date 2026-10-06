package dev.storeforge.stores.internal.domain;

import dev.storeforge.shared.tenancy.StoreId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A tenant. Tenant-directory row: not protected by RLS (see ADR-009). */
@Entity
@Table(name = "stores")
public class Store {

    @Id
    private UUID id;

    @Column(columnDefinition = "citext", nullable = false)
    private String handle;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StoreStatus status;

    @Column(columnDefinition = "bpchar(3)", nullable = false)
    private String currency;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Store() {
        // for JPA
    }

    public Store(StoreId id, StoreHandle handle, String name, String currency, UUID createdBy, Instant now) {
        this.id = id.value();
        this.handle = handle.value();
        this.name = name;
        this.status = StoreStatus.ACTIVE;
        this.currency = currency;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rename(String newName, Instant now) {
        this.name = newName;
        this.updatedAt = now;
    }

    public StoreId id() {
        return StoreId.of(id);
    }

    public String handle() {
        return handle;
    }

    public String name() {
        return name;
    }

    public StoreStatus status() {
        return status;
    }

    public String currency() {
        return currency;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
