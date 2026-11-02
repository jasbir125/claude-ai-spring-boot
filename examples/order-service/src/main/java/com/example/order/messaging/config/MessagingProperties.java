package com.example.order.messaging.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param source             producing service name written to every envelope
 * @param orderEventsTopic   topic owned by this service
 * @param userEventsTopic    topic owned by user-service, consumed here
 * @param userEventsDltTopic dead-letter topic of this service's user events consumer (owned by this service)
 * @param createTopics       declare the owned topics on startup (local/dev only; shared environments use IaC)
 * @param partitions         partitions of the declared topics
 * @param replicas           replication factor of the declared topics (≥ 3 in production)
 */
@Validated
@ConfigurationProperties("app.messaging")
public record MessagingProperties(
        @NotBlank String source,
        @NotBlank String orderEventsTopic,
        @NotBlank String userEventsTopic,
        @NotBlank String userEventsDltTopic,
        boolean createTopics,
        @Min(1) int partitions,
        @Min(1) short replicas) {
}
