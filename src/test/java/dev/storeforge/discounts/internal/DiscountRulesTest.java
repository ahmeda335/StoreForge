package dev.storeforge.discounts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.discounts.internal.DiscountRules.Rule;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DiscountRulesTest {

    static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    static Rule percent(long basisPoints) {
        return new Rule("SAVE", "PERCENTAGE", basisPoints, 0, NOW.minusSeconds(60), null, null, 0, "ACTIVE");
    }

    @Test
    void percentagesRoundHalfUpToTheMinorUnit() {
        assertThat(DiscountRules.amount(percent(3333), 1000)).as("33.33% of 10.00 = 3.333").isEqualTo(333);
        assertThat(DiscountRules.amount(percent(1250), 999)).as("12.5% of 9.99 = 1.24875").isEqualTo(125);
        assertThat(DiscountRules.amount(percent(500), 10)).as("5% of 0.10 = 0.005 → 0.01").isEqualTo(1);
        assertThat(DiscountRules.amount(percent(10000), 4999)).as("100% = free goods").isEqualTo(4999);
    }

    @Test
    void fixedAmountsNeverExceedTheSubtotal() {
        Rule tenOff = new Rule("TEN", "FIXED_AMOUNT", 1000, 0, NOW.minusSeconds(60), null, null, 0, "ACTIVE");
        assertThat(DiscountRules.amount(tenOff, 2500)).isEqualTo(1000);
        assertThat(DiscountRules.amount(tenOff, 600)).as("capped: never a negative total").isEqualTo(600);
    }

    @Test
    void everyReasonNotToApply() {
        Rule base = percent(1000);
        assertThat(DiscountRules.evaluate(null, "NOPE", 1000, false, NOW).reason()).isEqualTo("not_found");
        assertThat(reason(new Rule("C", "PERCENTAGE", 1000, 0, NOW.minusSeconds(60), null, null, 0, "DISABLED"), 1000)).isEqualTo("disabled");
        assertThat(reason(new Rule("C", "PERCENTAGE", 1000, 0, NOW.plusSeconds(60), null, null, 0, "ACTIVE"), 1000)).isEqualTo("not_started");
        assertThat(reason(new Rule("C", "PERCENTAGE", 1000, 0, NOW.minusSeconds(60), NOW, null, 0, "ACTIVE"), 1000))
                .as("ends_at is exclusive").isEqualTo("expired");
        assertThat(reason(new Rule("C", "PERCENTAGE", 1000, 0, NOW.minusSeconds(60), null, 5, 5, "ACTIVE"), 1000)).isEqualTo("usage_limit_reached");
        assertThat(reason(new Rule("C", "PERCENTAGE", 1000, 2000, NOW.minusSeconds(60), null, null, 0, "ACTIVE"), 1999)).isEqualTo("minimum_not_met");
        assertThat(reason(base, 0)).as("nothing to discount").isEqualTo("minimum_not_met");
        assertThat(DiscountRules.evaluate(base, "C", 1000, true, NOW).reason()).isEqualTo("already_used");

        var ok = DiscountRules.evaluate(new Rule("C", "PERCENTAGE", 1000, 0, NOW, NOW.plus(Duration.ofDays(1)), 5, 4, "ACTIVE"), "C", 1000, false, NOW);
        assertThat(ok.applicable()).as("starts_at inclusive, last use available").isTrue();
        assertThat(ok.amountMinor()).isEqualTo(100);
    }

    private static String reason(Rule rule, long subtotal) {
        return DiscountRules.evaluate(rule, rule.code(), subtotal, false, NOW).reason();
    }
}
