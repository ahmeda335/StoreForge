package dev.storeforge.discounts;

import dev.storeforge.shared.tenancy.StoreId;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Used by checkout. Both methods run in the caller's tenant transaction. */
public interface Discounts {

    /** Would {@code code} apply to this subtotal now? Read-only: an open checkout shows the result live. */
    Evaluation evaluate(StoreId storeId, String code, long subtotalMinor, @Nullable String customerEmail, Instant now);

    /**
     * Consumes one use, at checkout completion (MANDATORY transaction). Decided by a conditional
     * update, so two checkouts racing for the last use can't both win; a rollback of the completion
     * gives the use back.
     *
     * @return the discount amount in minor units
     * @throws DiscountUnavailableException (409) if the code no longer applies (expired, used up, ...)
     */
    long redeem(StoreId storeId, String code, UUID checkoutId, UUID orderId, long subtotalMinor, String customerEmail, Instant now);

    /**
     * @param reason why not applicable: {@code not_found}, {@code disabled}, {@code not_started}, {@code expired},
     *               {@code usage_limit_reached}, {@code minimum_not_met}, {@code already_used}
     */
    record Evaluation(String code, boolean applicable, long amountMinor, @Nullable String reason) {
    }
}
