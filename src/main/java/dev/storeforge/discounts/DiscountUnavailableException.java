package dev.storeforge.discounts;

import dev.storeforge.shared.error.StoreForgeException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** The code stopped applying between showing the total and completing (expired, last use taken, ...). */
@Getter
public class DiscountUnavailableException extends StoreForgeException {

    private final String reason;

    public DiscountUnavailableException(String reason) {
        super(HttpStatus.CONFLICT, "discount_unavailable", "The discount code can no longer be applied (" + reason + "). "
                + "Review the updated total and try again.");
        this.reason = reason;
    }
}
