package com.example.order.messaging.consumer;

import com.example.order.messaging.config.ConsumerProperties;
import com.example.order.repository.ProcessedEventRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes inbox rows after the retention period. Keep the retention longer than the redelivery window (topic
 * retention / replay horizon); after that, the user version check still drops replays. Idempotent, safe in every
 * replica.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboxCleanup {

    private final ProcessedEventRepository inbox;
    private final ConsumerProperties props;
    private final Clock clock;

    @Scheduled(cron = "${app.messaging.consumer.inbox-cleanup-cron:0 45 * * * *}")
    public void deleteExpired() {
        int deleted = inbox.deleteProcessedBefore(clock.instant().minus(props.inboxRetention()));
        if (deleted > 0) {
            log.info("Deleted {} processed event IDs from the inbox", deleted);
        }
    }
}
