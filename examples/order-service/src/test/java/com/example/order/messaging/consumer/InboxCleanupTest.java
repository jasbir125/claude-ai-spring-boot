package com.example.order.messaging.consumer;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.order.messaging.config.ConsumerProperties;
import com.example.order.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InboxCleanupTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    @Mock
    private ProcessedEventRepository inbox;

    @Test
    void deletesRowsOlderThanRetention() {
        var props = new ConsumerProperties(4, Duration.ofMillis(500), 2.0, Duration.ofSeconds(10), Duration.ofDays(30));
        when(inbox.deleteProcessedBefore(NOW.minus(Duration.ofDays(30)))).thenReturn(2);

        new InboxCleanup(inbox, props, Clock.fixed(NOW, ZoneOffset.UTC)).deleteExpired();

        verify(inbox).deleteProcessedBefore(NOW.minus(Duration.ofDays(30)));
    }
}
