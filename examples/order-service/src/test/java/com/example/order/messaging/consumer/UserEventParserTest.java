package com.example.order.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.order.messaging.config.NonRetryableEventException;
import com.example.order.messaging.event.user.UserDeactivated;
import com.example.order.messaging.event.user.UserRegistered;
import com.example.order.messaging.event.user.UserUpdated;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Consumer-side contract test: every user-service example (copied in contracts/events/consumed) must be readable. */
class UserEventParserTest {

    private static final UUID EVENT_ID = UUID.fromString("0b4f8c1e-7d2a-4f5b-9c3e-1a2b3c4d5e6f");

    private final UserEventParser parser = new UserEventParser(JsonMapper.builder().build());

    @Test
    void userServiceContractExamples_areParsed() throws IOException {
        assertThat(parser.parse(example("user-registered-v1.json"))).hasValueSatisfying(e -> {
            assertThat(e.eventId()).isEqualTo(EVENT_ID);
            assertThat(e.correlationId()).isEqualTo("checkout-42");
            assertThat(e.event()).isEqualTo(new UserRegistered(42L, 0L));
        });
        assertThat(parser.parse(example("user-updated-v1.json")))
                .hasValueSatisfying(e -> assertThat(e.event()).isEqualTo(new UserUpdated(42L, 1L)));
        assertThat(parser.parse(example("user-deactivated-v1.json"))).hasValueSatisfying(e -> assertThat(e.event())
                .isEqualTo(new UserDeactivated(42L, Instant.parse("2026-10-31T10:15:30Z"), 2L)));
    }

    @Test
    void personalData_isNeverRead() throws IOException {
        String parsed = parser.parse(example("user-registered-v1.json")).orElseThrow().toString();

        assertThat(parsed).doesNotContain("ada@example.com").doesNotContain("Ada Lovelace");
    }

    @Test
    void unknownEventType_orOtherSchemaVersion_isSkipped() {
        assertThat(parser.parse(envelope("UserMerged", 1, "{\"userId\":1,\"version\":0}"))).isEmpty();
        assertThat(parser.parse(envelope("UserRegistered", 2, "{\"userId\":1,\"version\":0}"))).isEmpty();
    }

    @Test
    void missingCorrelationId_isAllowed() {
        String json = """
                {"eventId":"%s","eventType":"UserUpdated","schemaVersion":1,"payload":{"userId":1,"version":3}}
                """.formatted(EVENT_ID);

        assertThat(parser.parse(json)).hasValueSatisfying(e -> assertThat(e.correlationId()).isNull());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not json", "[1,2]", "\"text\""})
    void malformedValue_isNonRetryable(String value) {
        assertThatThrownBy(() -> parser.parse(value)).isInstanceOf(NonRetryableEventException.class);
    }

    @Test
    void invalidJson_errorDoesNotQuoteThePayload() {
        assertThatThrownBy(() -> parser.parse("{\"email\":\"ada@example.com\""))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageNotContaining("ada@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"eventType\":\"UserRegistered\",\"schemaVersion\":1,\"payload\":{\"userId\":1,\"version\":0}}",
            "{\"eventId\":\"nope\",\"eventType\":\"UserRegistered\",\"schemaVersion\":1,\"payload\":{\"userId\":1,\"version\":0}}",
            "{\"eventId\":\"" + "0b4f8c1e-7d2a-4f5b-9c3e-1a2b3c4d5e6f" + "\",\"eventType\":\"\",\"schemaVersion\":1}",
            "{\"eventId\":\"0b4f8c1e-7d2a-4f5b-9c3e-1a2b3c4d5e6f\",\"eventType\":\"UserRegistered\",\"schemaVersion\":\"1\"}",
            "{\"eventId\":\"0b4f8c1e-7d2a-4f5b-9c3e-1a2b3c4d5e6f\",\"eventType\":\"UserRegistered\",\"schemaVersion\":1}",
            "{\"eventId\":\"0b4f8c1e-7d2a-4f5b-9c3e-1a2b3c4d5e6f\",\"eventType\":\"UserRegistered\",\"schemaVersion\":1,\"payload\":{\"version\":0}}",
            "{\"eventId\":\"0b4f8c1e-7d2a-4f5b-9c3e-1a2b3c4d5e6f\",\"eventType\":\"UserRegistered\",\"schemaVersion\":1,\"payload\":{\"userId\":0,\"version\":0}}",
            "{\"eventId\":\"0b4f8c1e-7d2a-4f5b-9c3e-1a2b3c4d5e6f\",\"eventType\":\"UserUpdated\",\"schemaVersion\":1,\"payload\":{\"userId\":1,\"version\":-1}}",
            "{\"eventId\":\"0b4f8c1e-7d2a-4f5b-9c3e-1a2b3c4d5e6f\",\"eventType\":\"UserDeactivated\",\"schemaVersion\":1,\"payload\":{\"userId\":1,\"version\":1,\"deactivatedAt\":\"yesterday\"}}"
    })
    void invalidEnvelopeOrPayload_isNonRetryable(String value) {
        assertThatThrownBy(() -> parser.parse(value)).isInstanceOf(NonRetryableEventException.class);
    }

    private static String envelope(String type, int schemaVersion, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":%d,"correlationId":"c-1","payload":%s}
                """.formatted(EVENT_ID, type, schemaVersion, payload);
    }

    private String example(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/contracts/events/consumed/" + name)) {
            assertThat(in).as("contract example %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
