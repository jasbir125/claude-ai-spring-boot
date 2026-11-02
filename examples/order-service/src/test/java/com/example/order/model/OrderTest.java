package com.example.order.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void place_computesLineTotalsAndTotal_atCurrencyScale() {
        Order order = Order.place(42L, EUR, List.of(
                new NewOrderLine(" SKU-1 ", 2, new BigDecimal("10")),
                new NewOrderLine("SKU-2", 1, new BigDecimal("5.5"))), NOW);

        assertThat(order.getUserId()).isEqualTo(42L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.getCurrency()).isEqualTo("EUR");
        assertThat(order.getPlacedAt()).isEqualTo(NOW);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("25.50").hasToString("25.50");
        assertThat(order.getLines()).extracting(OrderLine::getLineNo).containsExactly(1, 2);
        assertThat(order.getLines().getFirst().getSku()).isEqualTo("SKU-1");
        assertThat(order.getLines().getFirst().getUnitPrice()).hasToString("10.00");
        assertThat(order.getLines().getFirst().getLineTotal()).hasToString("20.00");
    }

    @Test
    void place_usesMinorUnitsOfEachCurrency() {
        Order yen = Order.place(1L, Currency.getInstance("JPY"), List.of(new NewOrderLine("A", 3, new BigDecimal("400"))), NOW);
        Order dinar = Order.place(1L, Currency.getInstance("BHD"), List.of(new NewOrderLine("A", 1, new BigDecimal("1.005"))), NOW);

        assertThat(yen.getTotalAmount()).hasToString("1200");
        assertThat(dinar.getTotalAmount()).hasToString("1.005");
    }

    @Test
    void place_rejectsMoreDecimalsThanCurrencyAllows() {
        assertThatThrownBy(() -> Order.place(1L, Currency.getInstance("JPY"),
                List.of(new NewOrderLine("A", 1, new BigDecimal("1.5"))), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JPY");
    }

    @Test
    void place_withoutLines_isRejected() {
        assertThatThrownBy(() -> Order.place(1L, EUR, List.of(), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.place(1L, EUR, null, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lines_areReadOnly() {
        Order order = Order.place(1L, EUR, List.of(new NewOrderLine("A", 1, BigDecimal.ONE)), NOW);

        assertThatThrownBy(() -> order.getLines().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void cancel_isIdempotent_andKeepsFirstReason() {
        Order order = Order.place(1L, EUR, List.of(new NewOrderLine("A", 1, BigDecimal.ONE)), NOW);
        Instant later = NOW.plusSeconds(60);

        assertThat(order.cancel(CancellationReason.USER_DEACTIVATED, later)).isTrue();
        assertThat(order.cancel(CancellationReason.CUSTOMER_REQUEST, later.plusSeconds(60))).isFalse();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo(CancellationReason.USER_DEACTIVATED);
        assertThat(order.getCancelledAt()).isEqualTo(later);
    }
}
