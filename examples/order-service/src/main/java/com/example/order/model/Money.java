package com.example.order.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** ISO 4217 helpers: amounts are always held at the currency's number of minor units (USD 2, JPY 0, BHD 3). */
public final class Money {

    private Money() {
    }

    /** @return the currency for an upper-case ISO 4217 code that has minor units, or empty */
    public static Optional<Currency> currency(String code) {
        if (code == null || code.length() != 3 || !code.equals(code.toUpperCase(Locale.ROOT))) {
            return Optional.empty();
        }
        try {
            Currency currency = Currency.getInstance(code);
            // funds/metals/testing codes (XAU, XXX, ...) have no minor units and are not valid order currencies
            return currency.getDefaultFractionDigits() < 0 ? Optional.empty() : Optional.of(currency);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** @return {@code true} when the amount needs no more decimals than the currency allows */
    public static boolean fitsMinorUnits(BigDecimal amount, Currency currency) {
        return amount.stripTrailingZeros().scale() <= currency.getDefaultFractionDigits();
    }

    /**
     * Rescales an amount to the currency's minor units without rounding.
     *
     * @throws ArithmeticException when the amount has more decimals than the currency allows
     */
    public static BigDecimal normalize(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        return amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
    }
}
