package dev.storeforge.orders.internal.domain;

import dev.storeforge.shared.error.StoreForgeException;
import org.springframework.http.HttpStatus;

public final class OrderExceptions {

    private OrderExceptions() {
    }

    public static class OrderNotFound extends StoreForgeException {
        public OrderNotFound() {
            super(HttpStatus.NOT_FOUND, "order_not_found", "Order not found.");
        }
    }

    /** The requested transition is not allowed from the order's current state. */
    public static class InvalidTransition extends StoreForgeException {
        public InvalidTransition(String code, String detail) {
            super(HttpStatus.CONFLICT, code, detail);
        }
    }

    /** Erasure would remove the address of an order that may still have to ship or be refunded. */
    public static class CustomerHasOpenOrders extends StoreForgeException {
        public CustomerHasOpenOrders(int count) {
            super(HttpStatus.CONFLICT, "customer_has_open_orders",
                    "The customer has " + count + " open order(s). Close or cancel them before erasing the customer's data.");
        }
    }
}
