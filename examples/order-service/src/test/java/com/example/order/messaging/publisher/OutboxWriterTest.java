package com.example.order.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.order.config.Correlation;
import com.example.order.messaging.config.MessagingProperties;
import com.example.order.messaging.event.EventEnvelope;
import com.example.order.messaging.event.OrderCancelled;
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
    private static final String TOPIC = "order.order.events.v1";

    @Mock
    private OutboxEventRepository repository;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private OutboxWriter writer;

    @BeforeEach
    void setUp() {
        var props = new MessagingProperties("order-service", TOPIC, "user.user.events.v1", "dlt", false, 1, (short) 1);
        writer = new OutboxWriter(repository, jsonMapper, Clock.fixed(NOW, ZoneOffset.UTC), props);
        when(repository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void append_storesSerializedEnvelope_withCorrelationAndCausation() {
        MDC.put(Correlation.MDC_KEY, "checkout-42");
        UUID cause = UUID.randomUUID();

        EventEnvelope<Object> envelope = writer.append(TOPIC, "Order", "7", "OrderCancelled", 1,
                new OrderCancelled(7L, 42L, "USER_DEACTIVATED", NOW, 1L), cause);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(saved.capture());
        OutboxEvent row = saved.getValue();
        assertThat(row.getId()).isEqualTo(envelope.eventId());
        assertThat(row.getTopic()).isEqualTo(TOPIC);
        assertThat(row.getAggregateId()).isEqualTo("7");
        assertThat(row.getPublishedAt()).isNull();

        JsonNode json = jsonMapper.readTree(row.getPayload());
        assertThat(json.get("correlationId").asString()).isEqualTo("checkout-42");
        assertThat(json.get("causationId").asString()).isEqualTo(cause.toString());
        assertThat(json.get("source").asString()).isEqualTo("order-service");
        assertThat(json.get("payload").get("reason").asString()).isEqualTo("USER_DEACTIVATED");
    }

    @Test
    void append_withoutCausation_andInvalidCorrelationId() {
        MDC.put(Correlation.MDC_KEY, "bad value\nwith newline");

        EventEnvelope<Object> envelope = writer.append(TOPIC, "Order", "7", "OrderCancelled", 1,
                new OrderCancelled(7L, 42L, "CUSTOMER_REQUEST", NOW, 1L), null);

        assertThat(envelope.causationId()).isNull();
        assertThat(UUID.fromString(envelope.correlationId())).isNotNull();
    }
}
