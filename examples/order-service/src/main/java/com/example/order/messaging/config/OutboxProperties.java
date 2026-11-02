package com.example.order.messaging.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param batchSize   rows locked and published per relay run; keep small, the batch holds row locks
 * @param sendTimeout max wait for the broker acknowledgement of one record
 * @param retention   published rows older than this are deleted by the cleanup job
 */
@Validated
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        @Min(1) @Max(1000) int batchSize,
        @NotNull Duration sendTimeout,
        @NotNull Duration retention) {
}
