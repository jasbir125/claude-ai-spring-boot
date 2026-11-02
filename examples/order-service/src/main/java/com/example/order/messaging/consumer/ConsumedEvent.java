package com.example.order.messaging.consumer;

import com.example.order.messaging.event.user.UserEvent;
import java.util.UUID;

/** A validated user event with the envelope fields the listener needs. */
public record ConsumedEvent(UUID eventId, String eventType, String correlationId, UserEvent event) {
}
