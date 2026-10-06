package dev.storeforge.stores.internal.domain;

import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.stores.Permission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A named set of permissions within one store (tenant data: RLS-protected). The owner role is
 * special: it implicitly has every permission, stored as an empty array, so permissions added to
 * the catalog later automatically apply to owners.
 */
@Entity
@Table(name = "roles")
public class Role {

    public static final String OWNER = "Owner";
    public static final String STAFF = "Staff";

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID storeId;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_owner", nullable = false)
    private boolean owner;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private String[] permissions;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Role() {
        // for JPA
    }

    private Role(StoreId storeId, String name, boolean owner, Set<Permission> permissions, Instant now) {
        this.id = Ids.newId();
        this.storeId = storeId.value();
        this.name = name;
        this.owner = owner;
        this.permissions = owner ? new String[0] : permissions.stream().map(Permission::code).sorted().toArray(String[]::new);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Role owner(StoreId storeId, Instant now) {
        return new Role(storeId, OWNER, true, Set.of(), now);
    }

    public static Role custom(StoreId storeId, String name, Set<Permission> permissions, Instant now) {
        return new Role(storeId, name, false, permissions, now);
    }

    public static Role defaultStaff(StoreId storeId, Instant now) {
        return custom(storeId, STAFF, EnumSet.of(Permission.PRODUCTS_READ, Permission.PRODUCTS_WRITE,
                Permission.INVENTORY_READ, Permission.INVENTORY_WRITE, Permission.ORDERS_READ), now);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean isOwner() {
        return owner;
    }

    /** Effective permissions (all of them for the owner role). */
    public Set<Permission> permissions() {
        if (owner) {
            return EnumSet.allOf(Permission.class);
        }
        return toPermissions(permissions);
    }

    public static Set<Permission> toPermissions(String[] codes) {
        return Arrays.stream(codes)
                .map(code -> Permission.fromCode(code).orElseThrow(() -> new IllegalStateException("Unknown permission " + code)))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
    }
}
