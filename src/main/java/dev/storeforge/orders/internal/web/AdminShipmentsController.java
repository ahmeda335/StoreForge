package dev.storeforge.orders.internal.web;

import dev.storeforge.identity.UserPrincipal;
import dev.storeforge.orders.internal.application.ShipmentService;
import dev.storeforge.orders.internal.persistence.ShipmentRecords.Shipment;
import dev.storeforge.shared.tenancy.StoreId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shipments of an order: partial fulfilment with tracking. */
@RestController
@RequestMapping("/admin/v1/stores/{storeId}/orders/{orderId}/shipments")
@RequiredArgsConstructor
class AdminShipmentsController {

    private final ShipmentService shipments;

    record Line(@NotNull UUID variantId, @NotNull @Min(1) Integer quantity) {
    }

    /** {@code lines} omitted = everything still unshipped. */
    record ShipRequest(@Nullable @Size(min = 1, max = 100) List<@Valid Line> lines, @Nullable @Size(max = 50) String carrier,
                       @Nullable @Size(max = 100) String trackingNumber) {
    }

    record ShipmentResponse(UUID id, String status, @Nullable String carrier, @Nullable String trackingNumber, Map<UUID, Integer> items,
                            Instant shippedAt, @Nullable Instant deliveredAt, @Nullable Instant cancelledAt) {
        static ShipmentResponse of(Shipment s) {
            return new ShipmentResponse(s.id(), s.status(), s.carrier(), s.trackingNumber(), s.items(), s.shippedAt(), s.deliveredAt(),
                    s.cancelledAt());
        }
    }

    @PostMapping
    ResponseEntity<ShipmentResponse> ship(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID orderId,
                                          @Valid @RequestBody ShipRequest body) {
        Shipment shipment = shipments.ship(user.userId(), StoreId.of(storeId), orderId,
                body.lines() == null ? null : body.lines().stream().map(l -> new ShipmentService.ShipLine(l.variantId(), l.quantity())).toList(),
                body.carrier(), body.trackingNumber());
        return ResponseEntity.created(URI.create("/admin/v1/stores/" + storeId + "/orders/" + orderId + "/shipments/" + shipment.id()))
                .body(ShipmentResponse.of(shipment));
    }

    @GetMapping
    Map<String, List<ShipmentResponse>> list(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID orderId) {
        return Map.of("data", shipments.list(user.userId(), StoreId.of(storeId), orderId).stream().map(ShipmentResponse::of).toList());
    }

    @PostMapping("/{shipmentId}/delivery")
    ShipmentResponse delivered(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID orderId,
                               @PathVariable UUID shipmentId) {
        return ShipmentResponse.of(shipments.markDelivered(user.userId(), StoreId.of(storeId), orderId, shipmentId));
    }

    @PostMapping("/{shipmentId}/cancellation")
    ShipmentResponse cancel(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID storeId, @PathVariable UUID orderId,
                            @PathVariable UUID shipmentId) {
        return ShipmentResponse.of(shipments.cancel(user.userId(), StoreId.of(storeId), orderId, shipmentId));
    }
}
