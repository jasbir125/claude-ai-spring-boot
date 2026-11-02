package com.example.order.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.order.config.Correlation;
import com.example.order.messaging.event.user.UserRegistered;
import com.example.order.service.UserProjectionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class UserEventsListenerTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final ConsumerRecord<String, String> RECORD =
            new ConsumerRecord<>("user.user.events.v1", 0, 5L, "42", "{}");

    @Mock
    private UserEventParser parser;

    @Mock
    private UserProjectionService projection;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private UserEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new UserEventsListener(parser, projection, meters);
    }

    @Test
    void delegatesWithCorrelationIdInMdc_andClearsItAfterwards() {
        when(parser.parse("{}")).thenReturn(Optional.of(
                new ConsumedEvent(EVENT_ID, "UserRegistered", "checkout-42", new UserRegistered(42L, 0L))));
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(MDC.get(Correlation.MDC_KEY));
            return UserProjectionService.Outcome.APPLIED;
        }).when(projection).apply(any(), any());

        listener.on(RECORD);

        assertThat(seen.get()).isEqualTo("checkout-42");
        assertThat(MDC.get(Correlation.MDC_KEY)).isNull();
    }

    @Test
    void unsafeCorrelationId_isReplaced() {
        when(parser.parse("{}")).thenReturn(Optional.of(
                new ConsumedEvent(EVENT_ID, "UserRegistered", "bad\nvalue", new UserRegistered(42L, 0L))));
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(MDC.get(Correlation.MDC_KEY));
            return UserProjectionService.Outcome.APPLIED;
        }).when(projection).apply(any(), any());

        listener.on(RECORD);

        assertThat(UUID.fromString(seen.get())).isNotNull();
    }

    @Test
    void unsupportedEvent_isSkipped_andCounted() {
        when(parser.parse("{}")).thenReturn(Optional.empty());

        listener.on(RECORD);

        verifyNoInteractions(projection);
        assertThat(meters.counter("user.events.skipped").count()).isEqualTo(1.0);
    }
}
