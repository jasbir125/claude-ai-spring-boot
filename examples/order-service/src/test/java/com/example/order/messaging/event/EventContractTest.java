package com.example.order.messaging.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Events are a public API. Each event serialized in its envelope must match the checked-in example in
 * {@code src/test/resources/contracts/events/produced}. A failing test means the contract changed: make it backward
 * compatible (add optional fields only) or bump {@code schemaVersion}, then update the example and docs/events.md.
 */
class EventContractTest {

    private static final UUID EVENT_ID = UUID.fromString("0b4f8c1e-7d2a-4f5b-9c3e-1a2b3c4d5e6f");
    private static final Instant AT = Instant.parse("2026-10-31T10:15:30Z");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    static Stream<Arguments> events() {
        return Stream.of(
                Arguments.of(new OrderPlaced(1001L, 42L, "EUR", new BigDecimal("25.50"), List.of(
                        new OrderPlaced.Line(1, "SKU-1", 2, new BigDecimal("10.00"), new BigDecimal("20.00")),
                        new OrderPlaced.Line(2, "SKU-2", 1, new BigDecimal("5.50"), new BigDecimal("5.50"))),
                        AT, 0L), null, "order-placed-v1.json"),
                Arguments.of(new OrderCancelled(1001L, 42L, "USER_DEACTIVATED", AT, 1L),
                        "7c9e6679-7425-40de-944b-e07fc1f90ae7", "order-cancelled-v1.json"));
    }

    @ParameterizedTest
    @MethodSource("events")
    void serializedEnvelope_matchesContractExample(OrderEvent event, String causationId, String example)
            throws IOException {
        var envelope = new EventEnvelope<>(EVENT_ID, event.eventType(), event.schemaVersion(), AT, "order-service",
                Long.toString(event.orderId()), "checkout-42", causationId, event);

        JsonNode actual = jsonMapper.readTree(jsonMapper.writeValueAsString(envelope));

        assertThat(actual).isEqualTo(readExample(example));
        assertThat(actual.get("payload").has("eventType")).as("eventType belongs to the envelope only").isFalse();
    }

    @ParameterizedTest
    @MethodSource("events")
    void contractExample_canBeReadBack(OrderEvent event, String causationId, String example) throws IOException {
        JsonNode payload = readExample(example).get("payload");

        OrderEvent read = jsonMapper.treeToValue(payload, event.getClass());

        assertThat(read).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(event);
    }

    /** Amounts are plain decimal strings at the currency's scale, never JSON numbers or scientific notation. */
    @Test
    void amounts_areWrittenAsPlainDecimalStrings() {
        var yen = new OrderPlaced(1L, 42L, "JPY", new BigDecimal("1200"), List.of(
                new OrderPlaced.Line(1, "SKU-1", 3, new BigDecimal("400"), new BigDecimal("1200"))), AT, 0L);
        var dinar = new OrderPlaced(2L, 42L, "BHD", new BigDecimal("0.000"), List.of(
                new OrderPlaced.Line(1, "SKU-1", 1, new BigDecimal("0.000"), new BigDecimal("0.000"))), AT, 0L);

        JsonNode yenJson = jsonMapper.readTree(jsonMapper.writeValueAsString(yen));
        JsonNode dinarJson = jsonMapper.readTree(jsonMapper.writeValueAsString(dinar));

        assertThat(yenJson.get("totalAmount").isString()).isTrue();
        assertThat(yenJson.get("totalAmount").asString()).isEqualTo("1200");
        assertThat(yenJson.get("lines").get(0).get("unitPrice").asString()).isEqualTo("400");
        assertThat(dinarJson.get("totalAmount").asString()).isEqualTo("0.000");
    }

    private JsonNode readExample(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/contracts/events/produced/" + name)) {
            assertThat(in).as("contract example %s", name).isNotNull();
            return jsonMapper.readTree(in);
        }
    }
}
