package com.example.order.messaging.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * An order was placed. Carries the complete order (event-carried state transfer), so consumers such as billing
 * or fulfilment never have to call order-service. Amounts are decimal strings at the currency's minor units
 * ({@code "25.50"} EUR, {@code "1200"} JPY): exact for every consumer, no floating-point parsing.
 */
public record OrderPlaced(long orderId, long userId, String currency,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount, List<Line> lines,
                          Instant placedAt, long version) implements OrderEvent {

    public OrderPlaced {
        lines = List.copyOf(lines);
    }

    public record Line(int lineNo, String sku, int quantity,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lineTotal) {
    }

    @Override
    public String eventType() {
        return "OrderPlaced";
    }

    @Override
    public int schemaVersion() {
        return 1;
    }
}
