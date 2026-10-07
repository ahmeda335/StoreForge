package dev.storeforge.stores.internal.web;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import dev.storeforge.stores.internal.web.StoreDtos.AuditEntryResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The store's audit trail (lives here, not in the audit module, so audit stays free of tenant authorization). */
@RestController
@RequiredArgsConstructor
class AuditLogController {

    private final AuditLog auditLog;
    private final StoreAccess access;
    private final TenantTransactions tenant;

    @GetMapping("/admin/v1/stores/{storeId}/audit-log")
    Page<AuditEntryResponse> list(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId,
                                  @RequestParam(required = false) @Nullable Integer limit,
                                  @RequestParam(required = false) @Nullable String cursor) {
        StoreId id = StoreId.of(storeId);
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        var page = tenant.inStore(id, () -> {
            access.require(user.userId(), id, Permission.AUDIT_READ);
            return auditLog.list(id, Page.limit(limit), after);
        });
        return new Page<>(page.data().stream().map(AuditEntryResponse::from).toList(), page.page());
    }
}
