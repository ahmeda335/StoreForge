package dev.storeforge.orders.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.storeforge.orders.Orders.OrderPlacement;
import dev.storeforge.orders.Orders.PlacedLine;
import dev.storeforge.orders.Orders.ShippingAddress;
import dev.storeforge.orders.internal.domain.Order.FinancialStatus;
import dev.storeforge.orders.internal.domain.Order.FulfillmentStatus;
import dev.storeforge.orders.internal.domain.Order.Status;
import dev.storeforge.shared.tenancy.StoreId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every legal and every illegal transition of the order state machine, without Spring or a database. */
class OrderStateMachineTest {

    static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    @Test
    void happyPathPaidThenFulfilledCloses() {
        Order order = newOrder();

        order.markPaid(NOW);
        order.fulfill(NOW.plusSeconds(60));

        assertThat(order.status()).isEqualTo(Status.CLOSED);
        assertThat(order.financialStatus()).isEqualTo(FinancialStatus.PAID);
        assertThat(order.fulfillmentStatus()).isEqualTo(FulfillmentStatus.FULFILLED);
    }

    @Test
    void unpaidOrdersCanBeCancelledAndAreVoided() {
        Order order = newOrder();

        order.cancel("customer_request", NOW);

        assertThat(order.status()).isEqualTo(Status.CANCELLED);
        assertThat(order.financialStatus()).isEqualTo(FinancialStatus.VOIDED);
        assertThat(order.cancelReason()).isEqualTo("customer_request");
    }

    @Test
    void illegalTransitionsAreRejectedWithSpecificCodes() {
        assertThatThrownBy(() -> newOrder().fulfill(NOW)).hasFieldOrPropertyWithValue("code", "order_not_paid");

        Order paid = newOrder();
        paid.markPaid(NOW);
        assertThatThrownBy(() -> paid.markPaid(NOW)).hasFieldOrPropertyWithValue("code", "order_already_paid");
        assertThatThrownBy(() -> paid.cancel("x", NOW)).hasFieldOrPropertyWithValue("code", "cancel_requires_refund");

        Order cancelled = newOrder();
        cancelled.cancel("x", NOW);
        assertThatThrownBy(() -> cancelled.markPaid(NOW)).hasFieldOrPropertyWithValue("code", "order_not_open");
        assertThatThrownBy(() -> cancelled.cancel("again", NOW)).hasFieldOrPropertyWithValue("code", "order_not_open");

        Order closed = newOrder();
        closed.markPaid(NOW);
        closed.fulfill(NOW);
        assertThatThrownBy(() -> closed.fulfill(NOW)).hasFieldOrPropertyWithValue("code", "order_not_open");
    }

    @Test
    void onlyOverdueUnpaidOrdersExpire() {
        Order order = newOrder();   // due NOW + 15 min

        assertThat(order.expireIfOverdue(NOW.plusSeconds(60))).isFalse();
        assertThat(order.expireIfOverdue(NOW.plusSeconds(16 * 60))).isTrue();
        assertThat(order.cancelReason()).isEqualTo(Order.REASON_PAYMENT_TIMEOUT);
        assertThat(order.expireIfOverdue(NOW.plusSeconds(3600))).as("idempotent").isFalse();

        Order paid = newOrder();
        paid.markPaid(NOW);
        assertThat(paid.expireIfOverdue(NOW.plusSeconds(3600))).as("paid orders never expire").isFalse();
    }

    private static Order newOrder() {
        var placement = new OrderPlacement(UUID.randomUUID(), null, "a@example.test", "USD",
                new ShippingAddress("Ada", "1 Main St", null, "Paris", null, "75001", "FR"),
                List.of(new PlacedLine(UUID.randomUUID(), "Lamp", "Default", null, 1000, 2, 2000)), 2000, 0, null, 0, 0, 2000);
        return Order.place(StoreId.of(UUID.randomUUID()), 1001, placement, NOW.plusSeconds(15 * 60), NOW);
    }
}
