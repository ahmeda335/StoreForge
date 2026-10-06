package dev.storeforge.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount in a currency, stored as an integer number of MINOR units (cents for USD, yen for JPY).
 *
 * <p>Why not double: 0.1 + 0.2 != 0.3 in binary floating point, and money errors compound across
 * line items, discounts and refunds. Why not BigDecimal everywhere: mixing scales (19.9 vs 19.90)
 * and rounding modes in arithmetic is error-prone. Integers in minor units make arithmetic exact;
 * decimal strings exist only at the API boundary.
 */
public record Money(long amountMinor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money ofMinor(long amountMinor, Currency currency) {
        return new Money(amountMinor, currency);
    }

    /**
     * Parses a decimal string such as {@code "19.99"}. Rejects more decimal places than the
     * currency has ("19.999 USD" is an input error, not something to round silently).
     */
    public static Money parse(String decimal, Currency currency) {
        BigDecimal value;
        try {
            value = new BigDecimal(decimal.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException("not a decimal amount");
        }
        int digits = Math.max(0, currency.getDefaultFractionDigits());
        if (value.scale() > digits) {
            throw new IllegalArgumentException("too many decimal places for " + currency.getCurrencyCode());
        }
        return new Money(value.movePointRight(digits).setScale(0, RoundingMode.UNNECESSARY).longValueExact(), currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public Money times(long quantity) {
        return new Money(Math.multiplyExact(amountMinor, quantity), currency);
    }

    public boolean isNegative() {
        return amountMinor < 0;
    }

    /** Decimal string for APIs, e.g. {@code "19.99"}; always with the currency's number of decimals. */
    public String toDecimalString() {
        return BigDecimal.valueOf(amountMinor, Math.max(0, currency.getDefaultFractionDigits())).toPlainString();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return toDecimalString() + " " + currency.getCurrencyCode();
    }
}
