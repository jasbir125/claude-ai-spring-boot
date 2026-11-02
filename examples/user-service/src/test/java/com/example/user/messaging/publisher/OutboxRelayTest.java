package com.example.user.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.user.messaging.config.OutboxProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafka;

    @Mock
    private TransactionTemplate tx;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        var props = new OutboxProperties(10, Duration.ofSeconds(1), Duration.ofDays(7));
        relay = new OutboxRelay(repository, kafka, tx, props, Clock.fixed(NOW, ZoneOffset.UTC), meters);
    }

    @Test
    void publishBatch_sendsInOrder_keyedByAggregate_andMarksPublished() {
        OutboxEvent first = event("1");
        OutboxEvent second = event("2");
        when(repository.lockNextBatch(10)).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult()));

        assertThat(relay.publishBatch()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(ProducerRecord::key).containsExactly("1", "2");
        assertThat(sent.getAllValues().getFirst().headers().lastHeader("eventType").value())
                .isEqualTo("UserRegistered".getBytes());
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isEqualTo(NOW);
        assertThat(meters.counter("outbox.published").count()).isEqualTo(2.0);
    }

    @Test
    void publishBatch_stopsAtFirstFailure_toKeepOrder() {
        OutboxEvent failing = event("1");
        OutboxEvent next = event("1");
        when(repository.lockNextBatch(10)).thenReturn(List.of(failing, next));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("broker down")));

        assertThat(relay.publishBatch()).isZero();

        verify(kafka, times(1)).send(any(ProducerRecord.class));
        assertThat(failing.getPublishedAt()).isNull();
        assertThat(failing.getAttempts()).isEqualTo(1);
        assertThat(next.getAttempts()).isZero();
        assertThat(meters.counter("outbox.publish.failures").count()).isEqualTo(1.0);
    }

    @Test
    void publishBatch_sendThrowsSynchronously_isRecordedAsFailure() {
        OutboxEvent failing = event("1");
        when(repository.lockNextBatch(10)).thenReturn(List.of(failing));
        when(kafka.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("producer closed"));

        assertThat(relay.publishBatch()).isZero();
        assertThat(failing.getAttempts()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void relay_runsBatchInTransaction() {
        when(tx.execute(any(TransactionCallback.class)))
                .thenAnswer(inv -> inv.<TransactionCallback<Integer>>getArgument(0).doInTransaction(null));
        when(repository.lockNextBatch(10)).thenReturn(List.of());

        relay.relay();

        verify(repository).lockNextBatch(10);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletePublished_removesRowsOlderThanRetention() {
        when(tx.execute(any(TransactionCallback.class)))
                .thenAnswer(inv -> inv.<TransactionCallback<Integer>>getArgument(0).doInTransaction(null));
        when(repository.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(3);

        relay.deletePublished();

        verify(repository).deletePublishedBefore(NOW.minus(Duration.ofDays(7)));
    }

    private static OutboxEvent event(String aggregateId) {
        return new OutboxEvent(UUID.randomUUID(), "User", aggregateId, "UserRegistered", 1, "user.user.events.v1",
                "{}", NOW);
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, String> sendResult() {
        return (SendResult<String, String>) org.mockito.Mockito.mock(SendResult.class);
    }
}
