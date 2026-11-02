package com.example.order.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope of every event this service publishes (public contract, see docs/events.md). Same shape as the
 * user-service envelope, so all services share one envelope format.
 *
 * @param eventId       unique per event; consumers deduplicate on it
 * @param eventType     e.g. {@code OrderPlaced}
 * @param schemaVersion major version of the payload schema
 * @param occurredAt    when the fact happened (UTC)
 * @param source        producing service
 * @param aggregateId   order ID; also the Kafka message key
 * @param correlationId business flow ID, propagated end to end
 * @param causationId   eventId of the message that caused this one ({@code UserDeactivated}), {@code null} for
 *                      API-triggered events
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String source,
        String aggregateId,
        String correlationId,
        String causationId,
        T payload) {
}
