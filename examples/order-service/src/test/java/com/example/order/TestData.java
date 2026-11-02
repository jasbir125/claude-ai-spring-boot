package com.example.order;

import com.example.order.model.KnownUser;
import com.example.order.model.NewOrderLine;
import com.example.order.model.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.springframework.test.util.ReflectionTestUtils;

/** Builders for unit tests; simulate what JPA assigns (id, version). */
public final class TestData {

    public static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    private TestData() {
    }

    public static Order order(long id, long userId, long version) {
        Order order = Order.place(userId, Currency.getInstance("EUR"),
                List.of(new NewOrderLine("SKU-1", 2, new BigDecimal("10.00"))), NOW);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "version", version);
        return order;
    }

    public static KnownUser activeUser(long userId, long version) {
        return KnownUser.registered(userId, version);
    }
}
