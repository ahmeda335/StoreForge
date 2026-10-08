package dev.storeforge.discounts.internal;

import dev.storeforge.discounts.Discounts.Evaluation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Pure discount arithmetic and eligibility, no I/O: every rule is unit-testable.
 * <ul>
 *   <li>Percentages are basis points of the SUBTOTAL (not shipping or tax), rounded HALF_UP to the minor unit:
 *       a third off 10.00 is 3.33. The rounding mode is a business decision, written down here.</li>
 *   <li>A fixed amount is capped at the subtotal: a discount can make goods free, never the total negative.</li>
 * </ul>
 */
public final class DiscountRules {

    private DiscountRules() {
    }

    public record Rule(String code, String kind, long value, long minimumSubtotalMinor, Instant startsAt, @Nullable Instant endsAt,
                       @Nullable Integer usageLimit, int usageCount, String status) {
    }

    public static Evaluation evaluate(@Nullable Rule rule, String code, long subtotalMinor, boolean alreadyUsedByCustomer, Instant now) {
        if (rule == null) {
            return new Evaluation(code, false, 0, "not_found");
        }
        String reason = null;
        if (!"ACTIVE".equals(rule.status())) {
            reason = "disabled";
        } else if (now.isBefore(rule.startsAt())) {
            reason = "not_started";
        } else if (rule.endsAt() != null && !now.isBefore(rule.endsAt())) {
            reason = "expired";
        } else if (rule.usageLimit() != null && rule.usageCount() >= rule.usageLimit()) {
            reason = "usage_limit_reached";
        } else if (subtotalMinor < rule.minimumSubtotalMinor() || subtotalMinor == 0) {
            reason = "minimum_not_met";
        } else if (alreadyUsedByCustomer) {
            reason = "already_used";
        }
        return reason != null ? new Evaluation(rule.code(), false, 0, reason) : new Evaluation(rule.code(), true, amount(rule, subtotalMinor), null);
    }

    public static long amount(Rule rule, long subtotalMinor) {
        long amount = "PERCENTAGE".equals(rule.kind())
                ? BigDecimal.valueOf(subtotalMinor).multiply(BigDecimal.valueOf(rule.value()))
                        .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact()
                : rule.value();
        return Math.min(amount, subtotalMinor);
    }
}
