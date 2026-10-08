package dev.storeforge.orders.internal.domain;

import dev.storeforge.orders.Orders.OrderPlacement;
import dev.storeforge.shared.ids.Ids;
import dev.storeforge.shared.tenancy.StoreId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Order aggregate root with an explicit state machine. Three dimensions, each with its own
 * lifecycle, so that "paid but not shipped" and "shipped but refunded" (later) are expressible:
 *
 * <pre>
 *   status:             OPEN ──(paid + fulfilled)──► CLOSED
 *                        └───(cancel / expire)─────► CANCELLED
 *   financial_status:   PENDING ──► PAID ──► PARTIALLY_REFUNDED ──► REFUNDED
 *                        │           └──────────────────────────────►┘
 *                        └───────► VOIDED            (cancelled before payment)
 *   fulfillment_status: UNFULFILLED ──► FULFILLED    (only when paid and not fully refunded)
 *
 *   A paid order can be cancelled only once it is fully refunded: money is never silently kept.
 * </pre>
 *
 * Every transition is a method that checks the current state and throws on an illegal move; the
 * database re-checks the forbidden combinations with CHECK constraints (V7). Transitions are
 * performed on a row locked FOR UPDATE, so concurrent "pay" and "cancel" serialize.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    public enum Status { OPEN, CLOSED, CANCELLED }

    public enum FinancialStatus { PENDING, PAID, PARTIALLY_REFUNDED, REFUNDED, VOIDED }

    public enum FulfillmentStatus { UNFULFILLED, PARTIALLY_FULFILLED, FULFILLED }

    public static final String REASON_PAYMENT_TIMEOUT = "PAYMENT_TIMEOUT";

    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID storeId;
    @Column(nullable = false)
    private long number;
    @Column(nullable = false)
    private UUID checkoutId;
    private UUID customerId;
    @Column(columnDefinition = "citext", nullable = false)
    private String email;
    @Column(columnDefinition = "bpchar(3)", nullable = false)
    private String currency;
    @Column(nullable = false)
    private long subtotalMinor;
    @Column(nullable = false)
    private long discountMinor;
    private String discountCode;
    @Column(nullable = false)
    private long shippingMinor;
    private String shippingRateName;
    @Column(nullable = false)
    private long taxMinor;
    @Column(nullable = false)
    private long totalMinor;
    @Column(nullable = false)
    private long refundedMinor;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FinancialStatus financialStatus;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FulfillmentStatus fulfillmentStatus;
    @Column(nullable = false)
    private String shipName;
    @Column(nullable = false)
    private String shipLine1;
    private String shipLine2;
    @Column(nullable = false)
    private String shipCity;
    private String shipRegion;
    @Column(nullable = false)
    private String shipPostalCode;
    @Column(columnDefinition = "bpchar(2)", nullable = false)
    private String shipCountry;
    @Column(nullable = false)
    private Instant paymentDueAt;
    private Instant paidAt;
    private Instant fulfilledAt;
    private Instant cancelledAt;
    private String cancelReason;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    @Getter(AccessLevel.NONE)
    private Long version;

    public static Order place(StoreId storeId, long number, OrderPlacement placement, Instant paymentDueAt, Instant now) {
        Order order = new Order();
        order.id = Ids.newId();
        order.storeId = storeId.value();
        order.number = number;
        order.checkoutId = placement.checkoutId();
        order.customerId = placement.customerId();
        order.email = placement.email();
        order.currency = placement.currency();
        order.subtotalMinor = placement.subtotalMinor();
        order.discountMinor = placement.discountMinor();
        order.discountCode = placement.discountCode();
        order.shippingRateName = placement.shippingRateName();
        order.shippingMinor = placement.shippingMinor();
        order.taxMinor = placement.taxMinor();
        order.totalMinor = placement.totalMinor();
        order.status = Status.OPEN;
        order.financialStatus = FinancialStatus.PENDING;
        order.fulfillmentStatus = FulfillmentStatus.UNFULFILLED;
        var address = placement.address();
        order.shipName = address.name();
        order.shipLine1 = address.line1();
        order.shipLine2 = address.line2();
        order.shipCity = address.city();
        order.shipRegion = address.region();
        order.shipPostalCode = address.postalCode();
        order.shipCountry = address.country();
        order.paymentDueAt = paymentDueAt;
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    /** Payment recorded (manually in P8; by the payment provider from P10). */
    public void markPaid(Instant now) {
        requireOpen();
        if (financialStatus != FinancialStatus.PENDING) {
            throw new OrderExceptions.InvalidTransition("order_already_paid", "The order is already paid.");
        }
        financialStatus = FinancialStatus.PAID;
        paidAt = now;
        updatedAt = now;
    }

    public boolean awaitingPayment() {
        return status == Status.OPEN && financialStatus == FinancialStatus.PENDING;
    }

    /** Everything remaining ships at once. */
    public void fulfill(Instant now) {
        recordShipment(true, now);
    }

    /**
     * A shipment left. {@code everythingShipped}: no unshipped quantity remains (computed by the
     * caller under this order's row lock). Paid + fully shipped closes the order.
     */
    public void recordShipment(boolean everythingShipped, Instant now) {
        requireOpen();
        if (financialStatus != FinancialStatus.PAID && financialStatus != FinancialStatus.PARTIALLY_REFUNDED) {
            throw new OrderExceptions.InvalidTransition("order_not_paid", "Only paid orders can be fulfilled.");
        }
        if (fulfillmentStatus == FulfillmentStatus.FULFILLED) {
            throw new OrderExceptions.InvalidTransition("order_already_fulfilled", "The order is already fulfilled.");
        }
        fulfillmentStatus = everythingShipped ? FulfillmentStatus.FULFILLED : FulfillmentStatus.PARTIALLY_FULFILLED;
        if (everythingShipped) {
            fulfilledAt = now;
            status = Status.CLOSED;   // paid + fulfilled: nothing left to do
        }
        updatedAt = now;
    }

    /** A shipment was cancelled (lost, not sent): its quantities are unshipped again, and a closed order reopens. */
    public void shipmentCancelled(boolean anythingStillShipped, Instant now) {
        if (status == Status.CANCELLED) {
            throw new OrderExceptions.InvalidTransition("order_not_open", "The order is cancelled.");
        }
        fulfillmentStatus = anythingStillShipped ? FulfillmentStatus.PARTIALLY_FULFILLED : FulfillmentStatus.UNFULFILLED;
        fulfilledAt = null;
        status = Status.OPEN;
        updatedAt = now;
    }

    /**
     * Money given back. Allowed after fulfillment too (returns). The amount check is repeated by the
     * database ({@code ck_orders_refunded}); the payments module checks it against the payment first.
     */
    public void recordRefund(long amountMinor, Instant now) {
        if (financialStatus != FinancialStatus.PAID && financialStatus != FinancialStatus.PARTIALLY_REFUNDED) {
            throw new OrderExceptions.InvalidTransition("order_not_refundable", "Only paid orders can be refunded.");
        }
        if (amountMinor <= 0 || refundedMinor + amountMinor > totalMinor) {
            throw new OrderExceptions.InvalidTransition("refund_exceeds_total", "Refunds can't exceed the order total.");
        }
        refundedMinor += amountMinor;
        financialStatus = refundedMinor == totalMinor ? FinancialStatus.REFUNDED : FinancialStatus.PARTIALLY_REFUNDED;
        updatedAt = now;
    }

    /** Cancelling a paid order needs a full refund first: money must never be silently kept or lost. */
    public void cancel(String reason, Instant now) {
        requireOpen();
        if (fulfillmentStatus != FulfillmentStatus.UNFULFILLED) {
            throw new OrderExceptions.InvalidTransition("order_has_shipments", "Orders with shipments can't be cancelled; cancel the shipments first.");
        }
        if (financialStatus == FinancialStatus.PAID || financialStatus == FinancialStatus.PARTIALLY_REFUNDED) {
            throw new OrderExceptions.InvalidTransition("cancel_requires_refund",
                    "Paid orders can only be cancelled after a full refund.");
        }
        if (financialStatus == FinancialStatus.PENDING) {
            financialStatus = FinancialStatus.VOIDED;
        }
        status = Status.CANCELLED;
        cancelledAt = now;
        cancelReason = reason;
        updatedAt = now;
    }

    /** @return true if the payment window has passed and the order was cancelled now */
    public boolean expireIfOverdue(Instant now) {
        if (status != Status.OPEN || financialStatus != FinancialStatus.PENDING || !paymentDueAt.isBefore(now)) {
            return false;
        }
        cancel(REASON_PAYMENT_TIMEOUT, now);
        return true;
    }

    private void requireOpen() {
        if (status != Status.OPEN) {
            throw new OrderExceptions.InvalidTransition("order_not_open", "The order is " + status.name().toLowerCase() + ".");
        }
    }

    public long version() { return version == null ? 0 : version; }
}
