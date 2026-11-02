package com.example.order.messaging.consumer;

import com.example.order.config.Correlation;
import com.example.order.service.UserProjectionService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Thin listener for {@code user.user.events.v1}: parse and validate the envelope, put the correlation ID into the
 * MDC (so events written while handling carry it), delegate to {@link UserProjectionService}. Failures are retried
 * and dead-lettered by the error handler ({@code KafkaConsumerConfig}); offsets are committed only after the
 * service transaction committed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventsListener {

    private final UserEventParser parser;
    private final UserProjectionService projection;
    private final MeterRegistry meters;

    @KafkaListener(id = "user-events", topics = "${app.messaging.user-events-topic}",
            groupId = "${spring.application.name}")
    public void on(ConsumerRecord<String, String> record) {
        Optional<ConsumedEvent> parsed = parser.parse(record.value());
        if (parsed.isEmpty()) {
            meters.counter("user.events.skipped").increment();
            log.debug("Skipped user event of unsupported type or schema version partition={} offset={}",
                    record.partition(), record.offset());
            return;
        }
        ConsumedEvent event = parsed.get();
        String correlationId = Correlation.isValid(event.correlationId())
                ? event.correlationId() : UUID.randomUUID().toString();
        MDC.put(Correlation.MDC_KEY, correlationId);
        try {
            projection.apply(event.eventId(), event.event());
        } finally {
            MDC.remove(Correlation.MDC_KEY);
        }
    }
}
