package com.example.order.model;

import java.math.BigDecimal;

/** Input for {@link Order#place}: one requested line, already validated at the API boundary. */
public record NewOrderLine(String sku, int quantity, BigDecimal unitPrice) {
}
