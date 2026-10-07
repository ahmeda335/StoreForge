package dev.storeforge.orders.internal.application;

import dev.storeforge.inventory.Inventory;
import dev.storeforge.jobs.Outbox;
import dev.storeforge.orders.OrderEvents;
import dev.storeforge.orders.Orders;
import dev.storeforge.orders.internal.domain.Order;
import dev.storeforge.orders.internal.domain.OrderExceptions;
import dev.storeforge.orders.internal.persistence.OrderRecords;
import dev.storeforge.orders.internal.persistence.OrderRepository;
import dev.storeforge.shared.tenancy.StoreId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class DefaultOrders implements Orders {

    private final OrderRepository orders;
    private final OrderRecords records;
    private final OrdersProperties properties;
    private final Inventory inventory;
    private final Outbox outbox;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Override
    public Duration reservationHold() {
        return properties.paymentWindow().plus(properties.reservationGrace());
    }

    /**
     * Runs inside the checkout-completion transaction: number, order row, line snapshots and the
     * first timeline event commit or roll back together with the stock reservation.
     * {@code uq_orders_store_checkout} guarantees one order per checkout even if a caller misbehaves.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PlacedOrder place(StoreId storeId, OrderPlacement placement) {
        Instant now = clock.instant();
        long number = records.nextOrderNumber(storeId);
        Instant paymentDueAt = now.plus(properties.paymentWindow());
        Order order = orders.saveAndFlush(Order.place(storeId, number, placement, paymentDueAt, now));
        records.insertItems(storeId, order.id(), placement.lines());
        records.event(storeId, order.id(), "PLACED", null,
                Map.of("checkoutId", placement.checkoutId().toString(), "total", placement.totalMinor()), now);
        // Same transaction as the order: the event exists iff the order does (transactional outbox).
        outbox.append(storeId, OrderEvents.AGGREGATE, order.id().toString(), OrderEvents.PLACED, OrderEvents.VERSION,
                new OrderEvents.Placed(order.id(), number, placement.totalMinor(), placement.currency(), placement.customerId()));
        meterRegistry.counter("storeforge.orders.created").increment();
        return new PlacedOrder(order.id(), number, paymentDueAt);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentTarget paymentTarget(StoreId storeId, UUID orderId) {
        Order order = orders.findByStoreIdAndId(storeId.value(), orderId).orElseThrow(OrderExceptions.OrderNotFound::new);
        return new PaymentTarget(order.id(), order.number(), order.email(), order.totalMinor(), order.currency(), order.awaitingPayment());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentOutcome applyPayment(StoreId storeId, UUID orderId, UUID paymentId, String method) {
        Order order = lock(storeId, orderId);
        if (!order.awaitingPayment()) {
            return PaymentOutcome.NOT_PAYABLE;
        }
        Instant now = clock.instant();
        order.markPaid(now);
        inventory.commit(storeId, order.checkoutId());
        records.event(storeId, order.id(), "PAID", null, Map.of("method", method, "paymentId", paymentId.toString()), now);
        publish(storeId, order, OrderEvents.PAID, new OrderEvents.Paid(order.id(), order.number(), order.totalMinor(),
                order.currency(), order.customerId(), method));
        meterRegistry.counter("storeforge.orders.paid", "method", method).increment();
        return PaymentOutcome.PAID;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPaymentFailure(StoreId storeId, UUID orderId, String code) {
        records.event(storeId, orderId, "PAYMENT_FAILED", null, Map.of("code", code), clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyRefund(StoreId storeId, UUID orderId, UUID refundId, long amountMinor, String reason, boolean compensation) {
        Order order = lock(storeId, orderId);
        Instant now = clock.instant();
        if (!compensation) {
            order.recordRefund(amountMinor, now);
            orders.flush();
            publish(storeId, order, OrderEvents.REFUNDED, new OrderEvents.Refunded(order.id(), order.number(), refundId, amountMinor,
                    order.currency(), order.refundedMinor()));
        }
        records.event(storeId, order.id(), "REFUNDED", null, Map.of("refundId", refundId.toString(), "amount", amountMinor,
                "reason", reason, "compensation", compensation), now);
    }

    private Order lock(StoreId storeId, UUID orderId) {
        return orders.lockByStoreIdAndId(storeId.value(), orderId).orElseThrow(OrderExceptions.OrderNotFound::new);
    }

    private void publish(StoreId storeId, Order order, String eventType, Object payload) {
        outbox.append(storeId, OrderEvents.AGGREGATE, order.id().toString(), eventType, OrderEvents.VERSION, payload);
    }
}
