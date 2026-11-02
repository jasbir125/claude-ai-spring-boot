package com.example.user.messaging.publisher;

import com.example.user.config.Correlation;
import com.example.user.messaging.config.MessagingProperties;
import com.example.user.messaging.event.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores an event in the outbox in the caller's transaction, so the event exists if and only if the state change
 * committed (no dual write). {@link OutboxRelay} publishes it afterwards.
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxEventRepository outbox;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final MessagingProperties props;

    /** Fails with {@code IllegalTransactionStateException} when called outside a transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope<Object> append(String topic, String aggregateType, String aggregateId, String eventType,
                                        int schemaVersion, Object payload) {
        Instant now = clock.instant();
        var envelope = new EventEnvelope<>(UUID.randomUUID(), eventType, schemaVersion, now, props.source(),
                aggregateId, currentCorrelationId(), null, payload);
        outbox.save(new OutboxEvent(envelope.eventId(), aggregateType, aggregateId, eventType, schemaVersion, topic,
                jsonMapper.writeValueAsString(envelope), now));
        return envelope;
    }

    private static String currentCorrelationId() {
        String fromContext = MDC.get(Correlation.MDC_KEY);
        return Correlation.isValid(fromContext) ? fromContext : UUID.randomUUID().toString();
    }
}
