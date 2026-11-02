package com.example.order.dto;

import com.example.order.model.CancellationReason;
import com.example.order.model.Order;
import com.example.order.model.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Amounts are at the currency's minor units (e.g. {@code 25.50} EUR, {@code 1200} JPY). */
public record OrderResponse(
        Long id,
        Long userId,
        OrderStatus status,
        String currency,
        BigDecimal totalAmount,
        List<OrderLineResponse> lines,
        Instant placedAt,
        CancellationReason cancellationReason,
        Instant cancelledAt,
        Long version) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getCurrency(),
                order.getTotalAmount(),
                order.getLines().stream().map(OrderLineResponse::from).toList(),
                order.getPlacedAt(),
                order.getCancellationReason(),
                order.getCancelledAt(),
                order.getVersion());
    }
}
