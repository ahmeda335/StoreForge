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
}
