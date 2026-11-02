package com.example.order.model;

/** {@code PLACED → CANCELLED} is the only transition; a cancelled order never changes again. */
public enum OrderStatus {
    PLACED,
    CANCELLED
}
