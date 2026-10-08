package dev.storeforge.orders.internal.application;

import dev.storeforge.audit.AuditLog;
import dev.storeforge.jobs.Outbox;
import dev.storeforge.orders.OrderEvents;
import dev.storeforge.orders.internal.domain.Order;
import dev.storeforge.orders.internal.domain.OrderExceptions;
import dev.storeforge.orders.internal.persistence.OrderRecords;
import dev.storeforge.orders.internal.persistence.OrderRepository;
import dev.storeforge.orders.internal.persistence.ShipmentRecords;
import dev.storeforge.orders.internal.persistence.ShipmentRecords.Shipment;
import dev.storeforge.shared.error.StoreForgeException;
import dev.storeforge.shared.tenancy.StoreId;
import dev.storeforge.shared.tenancy.TenantTransactions;
import dev.storeforge.stores.Permission;
import dev.storeforge.stores.StoreAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Shipments: partial fulfilment. Under the ORDER row lock (the P8 transition lock): compute what is
 * still unshipped (ordered − shipped in non-cancelled shipments), refuse anything beyond it, record
 * the shipment, move the order to PARTIALLY_FULFILLED / FULFILLED. Two concurrent shipments of the
 * same order queue on the lock, so they can't ship the same units twice.
 */
@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final OrderRepository orders;
    private final ShipmentRecords shipments;
    private final OrderRecords records;
    private final StoreAccess access;
    private final AuditLog audit;
    private final TenantTransactions tenant;
    private final Outbox outbox;
    private final Clock clock;

    public record ShipLine(UUID variantId, int quantity) {
    }

    /** @param lines what to ship; null = everything still unshipped */
    public Shipment ship(UUID userId, StoreId storeId, UUID orderId, @Nullable List<ShipLine> lines, @Nullable String carrier,
                         @Nullable String trackingNumber) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.ORDERS_WRITE);
            Order order = orders.lockByStoreIdAndId(storeId.value(), orderId).orElseThrow(OrderExceptions.OrderNotFound::new);
            Map<UUID, Integer> remaining = shipments.remaining(storeId, orderId);
            Map<UUID, Integer> shipping = new LinkedHashMap<>();
            if (lines == null) {
                remaining.forEach((variant, quantity) -> {
                    if (quantity > 0) {
                        shipping.put(variant, quantity);
                    }
                });
            } else {
                for (ShipLine line : lines) {
                    shipping.merge(line.variantId(), line.quantity(), Integer::sum);
                }
            }
            if (shipping.isEmpty()) {
                throw new NothingToShip();
            }
            shipping.forEach((variant, quantity) -> {
                if (quantity <= 0 || quantity > remaining.getOrDefault(variant, 0)) {
                    throw new QuantityExceedsRemaining(variant, remaining.getOrDefault(variant, 0));
                }
            });
            boolean complete = remaining.entrySet().stream().allMatch(e -> e.getValue() - shipping.getOrDefault(e.getKey(), 0) == 0);
            Instant now = clock.instant();
            order.recordShipment(complete, now);
            orders.flush();
            UUID shipmentId = shipments.insert(storeId, orderId, shipping, carrier, trackingNumber, userId, now);
            records.event(storeId, orderId, "SHIPPED", userId, Map.of("shipmentId", shipmentId.toString(), "complete", complete,
                    "trackingNumber", trackingNumber == null ? "" : trackingNumber), now);
            audit.recordUserAction(storeId, userId, "order.shipped", "order", orderId.toString(), Map.of("shipmentId", shipmentId.toString()));
            publish(storeId, order, OrderEvents.SHIPPED, new OrderEvents.Shipped(orderId, order.number(), shipmentId, carrier, trackingNumber, complete));
            if (complete) {
                // A milestone on the timeline, after the shipment that completed it (ids are time-ordered).
                records.event(storeId, orderId, "FULFILLED", userId, Map.of(), now);
                publish(storeId, order, OrderEvents.FULFILLED, new OrderEvents.Fulfilled(orderId, order.number()));
            }
            return shipments.find(storeId, shipmentId).orElseThrow();
        });
    }

    public List<Shipment> list(UUID userId, StoreId storeId, UUID orderId) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.ORDERS_READ);
            orders.findByStoreIdAndId(storeId.value(), orderId).orElseThrow(OrderExceptions.OrderNotFound::new);
            return shipments.forOrder(storeId, orderId);
        });
    }

    public Shipment markDelivered(UUID userId, StoreId storeId, UUID orderId, UUID shipmentId) {
        return transition(userId, storeId, orderId, shipmentId, "DELIVERED");
    }

    /** Only a shipment that hasn't been delivered: its units count as unshipped again, a closed order reopens. */
    public Shipment cancel(UUID userId, StoreId storeId, UUID orderId, UUID shipmentId) {
        return transition(userId, storeId, orderId, shipmentId, "CANCELLED");
    }

    private Shipment transition(UUID userId, StoreId storeId, UUID orderId, UUID shipmentId, String target) {
        return tenant.inStore(storeId, () -> {
            access.require(userId, storeId, Permission.ORDERS_WRITE);
            Order order = orders.lockByStoreIdAndId(storeId.value(), orderId).orElseThrow(OrderExceptions.OrderNotFound::new);
            Shipment shipment = shipments.find(storeId, shipmentId).filter(s -> s.orderId().equals(orderId)).orElseThrow(ShipmentNotFound::new);
            if (!"SHIPPED".equals(shipment.status())) {
                throw new OrderExceptions.InvalidTransition("shipment_not_in_transit", "Only shipments in transit can be " + target.toLowerCase() + ".");
            }
            Instant now = clock.instant();
            shipments.setStatus(storeId, shipmentId, target, now);
            if ("CANCELLED".equals(target)) {
                Map<UUID, Integer> remaining = shipments.remaining(storeId, orderId);
                Map<UUID, Integer> ordered = shipments.ordered(storeId, orderId);
                boolean anythingShipped = ordered.entrySet().stream().anyMatch(e -> remaining.getOrDefault(e.getKey(), 0) < e.getValue());
                order.shipmentCancelled(anythingShipped, now);
                orders.flush();
            }
            records.event(storeId, orderId, "CANCELLED".equals(target) ? "SHIPMENT_CANCELLED" : "DELIVERED", userId,
                    Map.of("shipmentId", shipmentId.toString()), now);
            return shipments.find(storeId, shipmentId).orElseThrow();
        });
    }

    private void publish(StoreId storeId, Order order, String type, Object payload) {
        outbox.append(storeId, OrderEvents.AGGREGATE, order.id().toString(), type, OrderEvents.VERSION, payload);
    }

    static class NothingToShip extends StoreForgeException {
        NothingToShip() {
            super(HttpStatus.CONFLICT, "nothing_to_ship", "Everything in this order has already been shipped.");
        }
    }

    static class QuantityExceedsRemaining extends StoreForgeException {
        QuantityExceedsRemaining(UUID variantId, int remaining) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "quantity_exceeds_remaining",
                    "Variant " + variantId + " has " + remaining + " unit(s) left to ship.");
        }
    }

    static class ShipmentNotFound extends StoreForgeException {
        ShipmentNotFound() {
            super(HttpStatus.NOT_FOUND, "shipment_not_found", "Shipment not found.");
        }
    }
}
