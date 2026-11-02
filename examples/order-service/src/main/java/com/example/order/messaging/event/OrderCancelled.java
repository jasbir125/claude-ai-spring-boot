package com.example.order.messaging.event;

import java.time.Instant;

/**
 * An order was cancelled.
 *
 * @param reason {@code CUSTOMER_REQUEST} (API) or {@code USER_DEACTIVATED} (user-service deactivated the user);
 *               consumers must accept unknown values, new reasons may be added
 */
public record OrderCancelled(long orderId, long userId, String reason, Instant cancelledAt, long version)
        implements OrderEvent {

    @Override
    public String eventType() {
        return "OrderCancelled";
    }

    @Override
    public int schemaVersion() {
        return 1;
    }
}
