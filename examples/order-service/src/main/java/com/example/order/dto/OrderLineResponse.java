package com.example.order.dto;

import com.example.order.model.OrderLine;
import java.math.BigDecimal;

public record OrderLineResponse(int lineNo, String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {

    public static OrderLineResponse from(OrderLine line) {
        return new OrderLineResponse(line.getLineNo(), line.getSku(), line.getQuantity(), line.getUnitPrice(),
                line.getLineTotal());
    }
}
