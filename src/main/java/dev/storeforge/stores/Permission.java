package dev.storeforge.stores;

import java.util.Arrays;
import java.util.Optional;

/**
 * The fixed catalog of permissions ({@code resource:action}). Defined in code, not by merchants:
 * every permission must be ENFORCED somewhere, and only code can do that. Roles are sets of these.
 * Permissions for modules not built yet are listed so roles can be designed ahead; they are
 * enforced as their modules arrive.
 */
public enum Permission {
    STORE_MANAGE("store:manage"),
    STAFF_READ("staff:read"),
    STAFF_MANAGE("staff:manage"),
    ROLES_MANAGE("roles:manage"),
    AUDIT_READ("audit:read"),
    PRODUCTS_READ("products:read"),
    PRODUCTS_WRITE("products:write"),
    INVENTORY_READ("inventory:read"),
    INVENTORY_WRITE("inventory:write"),
    ORDERS_READ("orders:read"),
    ORDERS_WRITE("orders:write"),
    ORDERS_REFUND("orders:refund"),
    CUSTOMERS_READ("customers:read");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<Permission> fromCode(String code) {
        return Arrays.stream(values()).filter(permission -> permission.code.equals(code)).findFirst();
    }
}
