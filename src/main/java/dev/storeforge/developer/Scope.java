package dev.storeforge.developer;

import dev.storeforge.stores.Permission;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What a credential may do, in the developer API's vocabulary. Each scope maps to exactly one
 * internal permission: granting a scope can never exceed what the granting merchant holds, and the
 * same {@code StoreAccess} decision protects both APIs.
 */
@Getter
@RequiredArgsConstructor
public enum Scope {
    READ_PRODUCTS("read_products", Permission.PRODUCTS_READ),
    WRITE_PRODUCTS("write_products", Permission.PRODUCTS_WRITE),
    READ_ORDERS("read_orders", Permission.ORDERS_READ),
    WRITE_ORDERS("write_orders", Permission.ORDERS_WRITE),
    READ_INVENTORY("read_inventory", Permission.INVENTORY_READ),
    WRITE_INVENTORY("write_inventory", Permission.INVENTORY_WRITE),
    /** Sensitive: customer PII. */
    READ_CUSTOMERS("read_customers", Permission.CUSTOMERS_READ);

    private final String code;
    private final Permission permission;

    public static Optional<Scope> fromCode(String code) {
        return Arrays.stream(values()).filter(scope -> scope.code.equals(code)).findFirst();
    }

    public static Set<Permission> permissionsOf(Collection<Scope> scopes) {
        return scopes.stream().map(Scope::permission).collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
    }
}
