package com.example.user.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.user.config.Correlation;
import com.example.user.messaging.config.MessagingProperties;
import com.example.user.messaging.event.EventEnvelope;
import com.example.user.messaging.event.UserDeactivated;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:15:30Z");

    @Mock
    private OutboxEventRepository repository;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private OutboxWriter writer;

    @BeforeEach
    void setUp() {
        var props = new MessagingProperties("user-service", "user.user.events.v1", false, 1, (short) 1);
        writer = new OutboxWriter(repository, jsonMapper, Clock.fixed(NOW, ZoneOffset.UTC), props);
        when(repository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void append_storesSerializedEnvelope_withCorrelationIdFromRequest() {
        MDC.put(Correlation.MDC_KEY, "checkout-42");

        EventEnvelope<Object> envelope = writer.append("user.user.events.v1", "User", "7", "UserDeactivated", 1,
                new UserDeactivated(7L, NOW, 2L));

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(saved.capture());
        OutboxEvent row = saved.getValue();
        assertThat(row.getId()).isEqualTo(envelope.eventId());
        assertThat(row.getTopic()).isEqualTo("user.user.events.v1");
        assertThat(row.getAggregateType()).isEqualTo("User");
        assertThat(row.getAggregateId()).isEqualTo("7");
        assertThat(row.getOccurredAt()).isEqualTo(NOW);
        assertThat(row.getPublishedAt()).isNull();

        JsonNode json = jsonMapper.readTree(row.getPayload());
        assertThat(json.get("eventId").asString()).isEqualTo(envelope.eventId().toString());
        assertThat(json.get("correlationId").asString()).isEqualTo("checkout-42");
        assertThat(json.get("source").asString()).isEqualTo("user-service");
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-10-31T10:15:30Z");
        assertThat(json.get("payload").get("userId").asLong()).isEqualTo(7L);
    }

    @Test
    void append_withoutOrInvalidCorrelationId_generatesOne() {
        MDC.put(Correlation.MDC_KEY, "bad value\nwith newline");

        EventEnvelope<Object> envelope = writer.append("t", "User", "7", "UserDeactivated", 1,
                new UserDeactivated(7L, NOW, 2L));

        assertThat(UUID.fromString(envelope.correlationId())).isNotNull();
    }
}
