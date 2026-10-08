package dev.storeforge.inventory.internal.web;

import dev.storeforge.developer.ApiClientPrincipal;
import dev.storeforge.developer.DeveloperApi;
import dev.storeforge.developer.RequiresScope;
import dev.storeforge.developer.Scope;
import dev.storeforge.inventory.internal.application.InventoryService;
import dev.storeforge.inventory.internal.application.InventoryService.Level;
import dev.storeforge.shared.idempotency.Idempotency;
import dev.storeforge.shared.pagination.Cursor;
import dev.storeforge.shared.pagination.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Developer API (2026-10): inventory levels (sync feed) and adjustments (idempotent). */
@RestController
@RequestMapping(DeveloperApi.STORE_PATH + "/inventory-items")
@RequiredArgsConstructor
class DeveloperInventoryController {

    private final InventoryService inventory;
    private final Idempotency idempotency;

    record InventoryItem(UUID id, UUID variantId, int onHand, int reserved, int available, boolean tracked) {
        static InventoryItem of(Level level) {
            return new InventoryItem(level.itemId(), level.variantId(), level.onHand(), level.reserved(), level.available(), level.tracked());
        }
    }

    record AdjustmentRequest(@NotNull @Min(-1_000_000) @Max(1_000_000) Integer delta, @NotBlank String reason,
                             @Nullable @Size(max = 500) String note) {
    }

    @GetMapping
    @RequiresScope(Scope.READ_INVENTORY)
    Page<InventoryItem> changedSince(@AuthenticationPrincipal ApiClientPrincipal client, @PathVariable String apiVersion,
                                     @PathVariable UUID storeId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant updatedAfter,
                                     @RequestParam(required = false) @Nullable Integer limit,
                                     @RequestParam(required = false) @Nullable String cursor) {
        var page = inventory.changedSince(client.actor(), client.storeId(), updatedAfter == null ? Instant.EPOCH : updatedAfter,
                Page.limit(limit), cursor == null ? null : Cursor.decode(cursor));
        return new Page<>(page.data().stream().map(InventoryItem::of).toList(), page.page());
    }

    @GetMapping("/{itemId}")
    @RequiresScope(Scope.READ_INVENTORY)
    InventoryItem get(@AuthenticationPrincipal ApiClientPrincipal client, @PathVariable String apiVersion, @PathVariable UUID storeId,
                      @PathVariable UUID itemId) {
        return InventoryItem.of(inventory.item(client.actor(), client.storeId(), itemId));
    }

    /**
     * Requires {@code Idempotency-Key}: "add 10 units" retried after a timeout must not add 20. The
     * fingerprint includes the credential, so another app reusing the key can't read this response.
     */
    @PostMapping("/{itemId}/adjustments")
    @RequiresScope(Scope.WRITE_INVENTORY)
    ResponseEntity<String> adjust(@AuthenticationPrincipal ApiClientPrincipal client, @PathVariable String apiVersion,
                                  @PathVariable UUID storeId, @PathVariable UUID itemId,
                                  @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String key,
                                  @Valid @RequestBody AdjustmentRequest body) {
        String fingerprint = "POST inventory-items/" + itemId + "/adjustments|" + body.delta() + "|" + body.reason() + "|" + body.note()
                + "|" + client.credentialId();
        var result = idempotency.execute(client.storeId(), "api.inventory.adjustment", key, fingerprint, 200,
                () -> InventoryItem.of(inventory.adjustItem(client.actor(), client.storeId(), itemId, body.delta(), body.reason(), body.note())));
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .body(result.jsonBody());
    }
}
