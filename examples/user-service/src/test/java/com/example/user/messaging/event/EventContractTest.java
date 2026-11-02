package com.example.user.messaging.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Events are a public API. Each event serialized in its envelope must match the checked-in example in
 * {@code src/test/resources/contracts/events}. A failing test means the contract changed: make it backward
 * compatible (add optional fields only) or bump {@code schemaVersion}, then update the example and docs/events.md.
 */
class EventContractTest {

    private static final UUID EVENT_ID = UUID.fromString("0b4f8c1e-7d2a-4f5b-9c3e-1a2b3c4d5e6f");
    private static final Instant AT = Instant.parse("2026-10-31T10:15:30Z");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    static Stream<Arguments> events() {
        return Stream.of(
                Arguments.of(new UserRegistered(42L, "ada@example.com", "Ada Lovelace", AT, 0L),
                        "user-registered-v1.json"),
                Arguments.of(new UserUpdated(42L, "ada@example.com", "Ada King", 1L), "user-updated-v1.json"),
                Arguments.of(new UserDeactivated(42L, AT, 2L), "user-deactivated-v1.json"));
    }

    @ParameterizedTest
    @MethodSource("events")
    void serializedEnvelope_matchesContractExample(UserEvent event, String example) throws IOException {
        var envelope = new EventEnvelope<>(EVENT_ID, event.eventType(), event.schemaVersion(), AT, "user-service",
                Long.toString(event.userId()), "checkout-42", null, event);

        JsonNode actual = jsonMapper.readTree(jsonMapper.writeValueAsString(envelope));

        assertThat(actual).isEqualTo(readExample(example));
        assertThat(actual.get("payload").has("eventType")).as("eventType belongs to the envelope only").isFalse();
    }

    @ParameterizedTest
    @MethodSource("events")
    void contractExample_canBeReadBack(UserEvent event, String example) throws IOException {
        JsonNode payload = readExample(example).get("payload");

        UserEvent read = jsonMapper.treeToValue(payload, event.getClass());

        assertThat(read).isEqualTo(event);
    }

    private JsonNode readExample(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/contracts/events/" + name)) {
            assertThat(in).as("contract example %s", name).isNotNull();
            return jsonMapper.readTree(in);
        }
    }
}
