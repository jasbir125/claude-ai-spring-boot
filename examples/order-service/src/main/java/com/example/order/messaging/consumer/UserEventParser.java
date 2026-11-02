package com.example.order.messaging.consumer;

import com.example.order.messaging.config.NonRetryableEventException;
import com.example.order.messaging.event.user.UserDeactivated;
import com.example.order.messaging.event.user.UserEvent;
import com.example.order.messaging.event.user.UserRegistered;
import com.example.order.messaging.event.user.UserUpdated;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the user-service envelope as a tolerant reader: fields are read by name, unknown fields are ignored, and
 * only the payload fields order-service needs are read (never email or display name).
 *
 * <ul>
 *   <li>unknown {@code eventType} or a {@code schemaVersion} other than 1 → empty (skipped; the contract allows new
 *       types, and a breaking v2 is published side by side with v1)</li>
 *   <li>not JSON, missing or invalid envelope/payload fields → {@link NonRetryableEventException} (dead-letter topic)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class UserEventParser {

    static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final JsonMapper jsonMapper;

    public Optional<ConsumedEvent> parse(String value) {
        if (value == null || value.isBlank()) {
            throw new NonRetryableEventException("Empty record value");
        }
        JsonNode envelope;
        try {
            envelope = jsonMapper.readTree(value);
        } catch (JacksonException ex) {
            // the parser message may quote the payload: do not propagate it
            throw new NonRetryableEventException("Record value is not valid JSON");
        }
        if (envelope == null || !envelope.isObject()) {
            throw new NonRetryableEventException("Envelope is not a JSON object");
        }
        UUID eventId = uuid(envelope, "eventId");
        String eventType = text(envelope, "eventType");
        int schemaVersion = (int) number(envelope, "schemaVersion");
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION || !isKnownType(eventType)) {
            return Optional.empty();
        }
        JsonNode payload = envelope.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new NonRetryableEventException("Missing payload");
        }
        String correlationId = envelope.hasNonNull("correlationId") ? envelope.get("correlationId").asString() : null;
        return Optional.of(new ConsumedEvent(eventId, eventType, correlationId, payload(eventType, payload)));
    }

    private static boolean isKnownType(String eventType) {
        return switch (eventType) {
            case UserRegistered.TYPE, UserUpdated.TYPE, UserDeactivated.TYPE -> true;
            default -> false;
        };
    }

    private static UserEvent payload(String eventType, JsonNode payload) {
        long userId = number(payload, "userId");
        long version = number(payload, "version");
        if (userId <= 0 || version < 0) {
            throw new NonRetryableEventException("payload.userId must be positive and payload.version not negative");
        }
        return switch (eventType) {
            case UserRegistered.TYPE -> new UserRegistered(userId, version);
            case UserUpdated.TYPE -> new UserUpdated(userId, version);
            case UserDeactivated.TYPE -> new UserDeactivated(userId, instant(payload, "deactivatedAt"), version);
            default -> throw new IllegalStateException("unreachable: " + eventType);
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new NonRetryableEventException("Missing or invalid field " + field);
        }
        return value.asString();
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new NonRetryableEventException("Missing or invalid field " + field);
        }
        return value.asLong();
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (IllegalArgumentException ex) {
            throw new NonRetryableEventException("Invalid field " + field);
        }
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeParseException ex) {
            throw new NonRetryableEventException("Invalid field " + field);
        }
    }
}
