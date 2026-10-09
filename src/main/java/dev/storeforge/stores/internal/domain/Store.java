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

    /**
     * Platform action: storefront, developer API and admin API refuse the store (admin: 403
     * {@code store_suspended}, the P3 rule). The merchant can still sign in and is told why.
     */
    public void suspend(Instant now) {
        if (status != StoreStatus.ACTIVE) {
            throw new StoreStatusConflict("Only an ACTIVE store can be suspended (it is " + status + ").");
        }
        status = StoreStatus.SUSPENDED;
        updatedAt = now;
    }

    public void reinstate(Instant now) {
        if (status != StoreStatus.SUSPENDED) {
            throw new StoreStatusConflict("Only a SUSPENDED store can be reinstated (it is " + status + ").");
        }
        status = StoreStatus.ACTIVE;
        updatedAt = now;
    }

    public static class StoreStatusConflict extends dev.storeforge.shared.error.StoreForgeException {
        StoreStatusConflict(String detail) {
            super(org.springframework.http.HttpStatus.CONFLICT, "store_status_conflict", detail);
        }
    }

    public StoreId id() {
        return StoreId.of(id);
    }
}
