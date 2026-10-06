package dev.storeforge.inventory.internal.web;

import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.inventory.internal.application.InventoryService;
import dev.storeforge.inventory.internal.application.InventoryService.Level;
import dev.storeforge.inventory.internal.persistence.InventoryStore.LedgerRow;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import dev.storeforge.shared.tenancy.StoreId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stock of one variant: level, manual adjustments, tracking flag, ledger. */
@RestController
@RequestMapping("/admin/v1/stores/{storeId}/variants/{variantId}/inventory")
class InventoryController {

    private final InventoryService inventory;

    InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    record AdjustmentRequest(@NotNull @Min(-1_000_000) @Max(1_000_000) Integer delta,
                             @NotBlank String reason,
                             @Nullable @Size(max = 500) String note) {
    }

    record TrackingRequest(@NotNull Boolean tracked) {
    }

    @GetMapping
    Level level(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID variantId) {
        return inventory.level(user.userId(), StoreId.of(storeId), variantId);
    }

    @PatchMapping
    Level tracking(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID variantId,
                   @Valid @RequestBody TrackingRequest body) {
        return inventory.setTracked(user.userId(), StoreId.of(storeId), variantId, body.tracked());
    }

    @PostMapping("/adjustments")
    Level adjust(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID variantId,
                 @Valid @RequestBody AdjustmentRequest body) {
        return inventory.adjust(user.userId(), StoreId.of(storeId), variantId, body.delta(), body.reason(), body.note());
    }

    @GetMapping("/transactions")
    Page<LedgerRow> ledger(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID variantId,
                           @RequestParam(required = false) @Nullable Integer limit,
                           @RequestParam(required = false) @Nullable String cursor) {
        return inventory.ledger(user.userId(), StoreId.of(storeId), variantId, Page.limit(limit),
                cursor == null ? null : Cursor.decode(cursor));
    }
}
