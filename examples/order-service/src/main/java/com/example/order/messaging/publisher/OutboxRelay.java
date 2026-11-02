package com.example.order.messaging.publisher;

import com.example.order.messaging.config.OutboxProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polling publisher: sends unpublished outbox rows to Kafka in order and marks them published only after the broker
 * acknowledged them. A crash between ack and commit re-sends the row, so delivery is at-least-once and consumers
 * deduplicate on {@code eventId}.
 *
 * <p>The transaction is held for one small, bounded batch (documented exception to "no transaction across a broker
 * send"); {@code FOR UPDATE SKIP LOCKED} makes it safe to run in every replica.
 */
@Component
@ConditionalOnProperty(prefix = "app.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class OutboxRelay {

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties props;
    private final Clock clock;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       OutboxProperties props, Clock clock, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
        this.published = Counter.builder("outbox.published").description("Outbox events sent to the broker")
                .register(meters);
        this.failures = Counter.builder("outbox.publish.failures").description("Failed outbox publish attempts")
                .register(meters);
        Gauge.builder("outbox.pending", outbox, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Outbox events not yet published").register(meters);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    public void relay() {
        Integer sent = tx.execute(status -> publishBatch());
        if (sent != null && sent > 0) {
            log.debug("Outbox relay published {} events", sent);
        }
    }

    /** Publishes one batch; stops at the first failure to keep per-aggregate order. Runs inside a transaction. */
    int publishBatch() {
        List<OutboxEvent> batch = outbox.lockNextBatch(props.batchSize());
        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                kafka.send(toRecord(event)).get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                event.markPublished(clock.instant());
                published.increment();
                sent++;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                event.recordFailedAttempt();
                failures.increment();
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException ex) {
                event.recordFailedAttempt();
                failures.increment();
                log.warn("Outbox publish failed eventId={} eventType={} attempts={}",
                        event.getId(), event.getEventType(), event.getAttempts(), ex);
                break;
            }
        }
        return sent;
    }

    /** Deletes published rows after the retention period. Idempotent, safe in every replica. */
    @Scheduled(cron = "${app.outbox.cleanup-cron:0 15 * * * *}")
    public void deletePublished() {
        Integer deleted = tx.execute(status ->
                outbox.deletePublishedBefore(clock.instant().minus(props.retention())));
        if (deleted != null && deleted > 0) {
            log.info("Deleted {} published outbox events", deleted);
        }
    }

    private static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        var record = new ProducerRecord<String, String>(event.getTopic(), event.getAggregateId(), event.getPayload());
        record.headers()
                .add("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8))
                .add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8))
                .add("schemaVersion", Integer.toString(event.getSchemaVersion()).getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
