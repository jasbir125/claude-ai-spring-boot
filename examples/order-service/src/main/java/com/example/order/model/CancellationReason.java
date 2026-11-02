package com.example.order.model;

public enum CancellationReason {
    /** Cancelled through {@code POST /api/v1/orders/{id}/cancel}. */
    CUSTOMER_REQUEST,
    /** Cancelled automatically because user-service deactivated the user ({@code UserDeactivated}). */
    USER_DEACTIVATED
}
