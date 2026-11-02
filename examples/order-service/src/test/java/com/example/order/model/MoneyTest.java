package com.example.order.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @ParameterizedTest
    @ValueSource(strings = {"EUR", "USD", "JPY", "BHD", "CHF"})
    void currency_acceptsIsoCodesWithMinorUnits(String code) {
        assertThat(Money.currency(code)).map(Currency::getCurrencyCode).contains(code);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"eur", "EURO", "EU", "ABC", "XAU", "XXX", ""})
    void currency_rejectsInvalidOrNonMonetaryCodes(String code) {
        assertThat(Money.currency(code)).isEmpty();
    }

    @Test
    void fitsMinorUnits_ignoresTrailingZeros() {
        Currency eur = Currency.getInstance("EUR");

        assertThat(Money.fitsMinorUnits(new BigDecimal("1.2300"), eur)).isTrue();
        assertThat(Money.fitsMinorUnits(new BigDecimal("1.234"), eur)).isFalse();
    }

    @Test
    void normalize_neverRounds() {
        Currency eur = Currency.getInstance("EUR");

        assertThat(Money.normalize(new BigDecimal("1.5000"), eur)).hasToString("1.50");
        assertThatThrownBy(() -> Money.normalize(new BigDecimal("1.505"), eur)).isInstanceOf(ArithmeticException.class);
    }
}
