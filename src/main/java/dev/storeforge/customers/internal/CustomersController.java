package dev.storeforge.customers.internal;

import dev.storeforge.customers.internal.CustomerStore.CustomerRow;
import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.shared.error.StoreForgeException;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Merchant view of the store's customers. Customer data is PII: requires customers:read. */
@RestController
@RequestMapping("/admin/v1/stores/{storeId}/customers")
@RequiredArgsConstructor
class CustomersController {

    private final CustomerStore customers;
    private final StoreAccess access;
    private final TenantTransactions tenant;
    private final CustomerPrivacyService privacy;

    @GetMapping
    Page<CustomerRow> list(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                           @RequestParam(required = false) @Nullable Integer limit,
                           @RequestParam(required = false) @Nullable String cursor) {
        StoreId id = StoreId.of(storeId);
        int pageSize = Page.limit(limit);
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        return tenant.inStore(id, () -> {
            access.require(user.userId(), id, Permission.CUSTOMERS_READ);
            return Page.of(customers.page(id, pageSize + 1, after), pageSize, row -> new Cursor(row.createdAt(), row.id()));
        });
    }

    @GetMapping("/{customerId}")
    CustomerRow get(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID customerId) {
        StoreId id = StoreId.of(storeId);
        return tenant.inStore(id, () -> {
            access.require(user.userId(), id, Permission.CUSTOMERS_READ);
            return customers.find(id, customerId).orElseThrow(CustomerNotFound::new);
        });
    }

    record ErasureRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** Everything the store holds about the customer, as a JSON download (right of access). */
    @GetMapping("/{customerId}/export")
    ResponseEntity<Map<String, Object>> export(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                                                        @PathVariable UUID customerId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "attachment; filename=\"customer-" + customerId + ".json\"")
                .body(privacy.export(user.userId(), StoreId.of(storeId), customerId));
    }

    /** Irreversible (right to erasure): personal data replaced everywhere; accounting records kept. */
    @PostMapping("/{customerId}/erasure")
    ResponseEntity<Void> erase(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                                        @PathVariable UUID customerId,
                                                        @Valid @RequestBody ErasureRequest body) {
        privacy.erase(user.userId(), StoreId.of(storeId), customerId, body.reason());
        return ResponseEntity.noContent().build();
    }

    static class CustomerNotFound extends StoreForgeException {
        CustomerNotFound() {
            super(HttpStatus.NOT_FOUND, "customer_not_found", "Customer not found.");
        }
    }
}
