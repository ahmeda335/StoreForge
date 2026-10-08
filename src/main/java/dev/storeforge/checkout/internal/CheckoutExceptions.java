package dev.storeforge.checkout.internal;

import dev.storeforge.shared.error.StoreForgeException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class CheckoutExceptions {

    private CheckoutExceptions() {
    }

    /** Unknown cart, or a cart token that doesn't match: indistinguishable on purpose. */
    static class CartNotFound extends StoreForgeException {
        CartNotFound() {
            super(HttpStatus.NOT_FOUND, "cart_not_found", "Cart not found.");
        }
    }

    static class CheckoutNotFound extends StoreForgeException {
        CheckoutNotFound() {
            super(HttpStatus.NOT_FOUND, "checkout_not_found", "Checkout not found.");
        }
    }

    static class CartClosed extends StoreForgeException {
        CartClosed() {
            super(HttpStatus.CONFLICT, "cart_closed", "This cart was already checked out.");
        }
    }

    static class CartEmpty extends StoreForgeException {
        CartEmpty() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "cart_empty", "The cart is empty.");
        }
    }

    static class CartFull extends StoreForgeException {
        CartFull(int max) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "cart_full", "A cart can hold at most " + max + " different items.");
        }
    }

    static class QuantityLimit extends StoreForgeException {
        QuantityLimit() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "quantity_limit", "Quantity per item must be between 1 and 100.");
        }
    }

    static class ItemUnavailable extends StoreForgeException {
        ItemUnavailable(UUID variantId) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "item_unavailable", "Item " + variantId + " is not available for sale.");
        }
    }

    static class ShippingRateNotAvailable extends StoreForgeException {
        ShippingRateNotAvailable() {
            super(HttpStatus.CONFLICT, "shipping_rate_not_available",
                    "The chosen shipping rate doesn't apply to this address or cart (any more). Choose one of the listed rates.");
        }
    }

    static class DiscountNotApplicable extends StoreForgeException {
        DiscountNotApplicable(String reason) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "discount_not_applicable", "This discount code can't be applied (" + reason + ").");
        }
    }

    static class CheckoutNotCompleted extends StoreForgeException {
        CheckoutNotCompleted() {
            super(HttpStatus.CONFLICT, "checkout_not_completed", "Complete the checkout before paying.");
        }
    }

    static class PaymentNotStarted extends StoreForgeException {
        PaymentNotStarted() {
            super(HttpStatus.NOT_FOUND, "payment_not_found", "No payment has been started for this checkout.");
        }
    }

    static class CheckoutNotOpen extends StoreForgeException {
        CheckoutNotOpen() {
            super(HttpStatus.CONFLICT, "checkout_not_open", "This checkout is already completed or expired.");
        }
    }

    static class CheckoutIncomplete extends StoreForgeException {
        CheckoutIncomplete(List<String> missing) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "checkout_incomplete", "Missing before completion: " + String.join(", ", missing) + ".");
        }
    }

    /**
     * The total changed between what the customer saw and now (a price was edited meanwhile). We
     * never commit the customer to an amount they did not see: they must review and confirm again.
     */
    static class TotalChanged extends StoreForgeException {
        TotalChanged(String expected, String actual) {
            super(HttpStatus.CONFLICT, "total_changed", "The total changed from " + expected + " to " + actual
                    + ". Review the checkout and confirm the new total.");
        }
    }

    static class InvalidCountry extends StoreForgeException {
        InvalidCountry(String country) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_country", "Unknown country code: " + country + ".");
        }
    }

    static class InvalidAmount extends StoreForgeException {
        InvalidAmount() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_amount", "expectedTotal is not a valid amount in the store currency.");
        }
    }
}
