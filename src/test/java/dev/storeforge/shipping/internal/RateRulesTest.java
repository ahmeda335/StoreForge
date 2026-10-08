package dev.storeforge.shipping.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.storeforge.shipping.internal.RateRules.Rate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateRulesTest {

    @Test
    void weightBandsAreHalfOpen() {
        Rate light = new Rate(List.of(), 0, 1000, 490, null, true);
        Rate heavy = new Rate(List.of(), 1000, null, 990, null, true);
        assertThat(RateRules.applies(light, "FR", 999)).isTrue();
        assertThat(RateRules.applies(light, "FR", 1000)).as("1000 g is not 'below 1000'").isFalse();
        assertThat(RateRules.applies(heavy, "FR", 1000)).isTrue();
        assertThat(RateRules.applies(heavy, "FR", 50_000)).as("no upper bound").isTrue();
    }

    @Test
    void countriesRestrictAndEmptyMeansEverywhere() {
        assertThat(RateRules.applies(new Rate(List.of("FR", "DE"), 0, null, 500, null, true), "GB", 10)).isFalse();
        assertThat(RateRules.applies(new Rate(List.of("FR", "DE"), 0, null, 500, null, true), "DE", 10)).isTrue();
        assertThat(RateRules.applies(new Rate(List.of(), 0, null, 500, null, true), "JP", 10)).isTrue();
        assertThat(RateRules.applies(new Rate(List.of(), 0, null, 500, null, false), "JP", 10)).as("inactive").isFalse();
    }

    @Test
    void freeShippingCountsTheSubtotalAfterDiscounts() {
        Rate rate = new Rate(List.of(), 0, null, 590, 5000L, true);
        assertThat(RateRules.price(rate, 5000)).as("threshold inclusive").isZero();
        assertThat(RateRules.price(rate, 4999)).isEqualTo(590);
    }
}
