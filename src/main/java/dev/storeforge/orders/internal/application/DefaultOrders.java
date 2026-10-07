package dev.storeforge.orders.internal.application;

import dev.storeforge.jobs.Outbox;
import dev.storeforge.orders.OrderEvents;
import dev.storeforge.orders.Orders;
import dev.storeforge.orders.internal.domain.Order;
import dev.storeforge.orders.internal.persistence.OrderRecords;
import dev.storeforge.orders.internal.persistence.OrderRepository;
import dev.storeforge.shared.tenancy.StoreId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
}
