package dev.storeforge.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    static final Currency USD = Currency.getInstance("USD");
    static final Currency JPY = Currency.getInstance("JPY");

    @ParameterizedTest
    @CsvSource({"19.99,1999", "19.9,1990", "0,0", "5,500", "0.01,1"})
    void parsesDecimalStringsIntoMinorUnits(String decimal, long minor) {
        assertThat(Money.parse(decimal, USD).amountMinor()).isEqualTo(minor);
    }

    @Test
    void respectsTheCurrencysDecimalPlaces() {
        assertThat(Money.parse("1500", JPY).amountMinor()).isEqualTo(1500);
        assertThatThrownBy(() -> Money.parse("15.5", JPY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("19.999", USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("abc", USD)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arithmeticIsExact() {
        Money tenCents = Money.parse("0.10", USD);
        Money twentyCents = Money.parse("0.20", USD);

        assertThat(tenCents.plus(twentyCents)).isEqualTo(Money.parse("0.30", USD));
        assertThat(Money.parse("19.99", USD).times(3).toDecimalString()).isEqualTo("59.97");
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.parse("1", USD).plus(Money.parse("1", JPY))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsWithTheCurrencysDecimals() {
        assertThat(Money.ofMinor(1990, USD).toDecimalString()).isEqualTo("19.90");
        assertThat(Money.ofMinor(1500, JPY).toDecimalString()).isEqualTo("1500");
    }
}
