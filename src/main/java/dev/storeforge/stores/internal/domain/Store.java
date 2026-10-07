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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A tenant. Tenant-directory row: not protected by RLS (see ADR-009). */
@Entity
@Table(name = "stores")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {

    @Id
    private UUID id;

    @Column(columnDefinition = "citext", nullable = false)
    @Getter
    private String handle;

    @Column(nullable = false)
    @Getter
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Getter
    private StoreStatus status;

    @Column(columnDefinition = "bpchar(3)", nullable = false)
    @Getter
    private String currency;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    @Getter
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

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
}
