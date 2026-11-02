package com.example.order.messaging.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Retries of the user events listener before a record goes to the dead-letter topic (blocking retries keep the
 * per-user order), and how long the inbox remembers processed event IDs.
 *
 * @param maxRetries      retries after the first attempt; non-retryable errors skip them
 * @param initialInterval first back-off
 * @param multiplier      back-off growth per retry
 * @param maxInterval     cap of a single back-off; keep retries × maxInterval well below {@code max.poll.interval.ms}
 * @param inboxRetention  processed event IDs older than this are deleted by the cleanup job
 */
@Validated
@ConfigurationProperties("app.messaging.consumer")
public record ConsumerProperties(
        @Min(0) @Max(10) int maxRetries,
        @NotNull Duration initialInterval,
        @DecimalMin("1.0") double multiplier,
        @NotNull Duration maxInterval,
        @NotNull Duration inboxRetention) {
}
