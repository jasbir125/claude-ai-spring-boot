package com.example.order.messaging.event;

/**
 * Domain events published on the order events topic. Payloads carry the order {@code version} so consumers can
 * ignore stale or out-of-order events.
 */
public sealed interface OrderEvent permits OrderPlaced, OrderCancelled {

    long orderId();

    long version();

    /** Contract name, written to the envelope; not part of the payload. */
    String eventType();

    /** Major schema version of the payload; not part of the payload. */
    int schemaVersion();
}
