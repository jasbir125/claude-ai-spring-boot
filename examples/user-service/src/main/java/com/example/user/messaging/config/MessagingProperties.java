package com.example.user.messaging.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param source          producing service name written to every envelope
 * @param userEventsTopic topic owned by this service
 * @param createTopics    declare topics on startup (local/dev only; shared environments provision topics via IaC)
 * @param partitions      partitions of the declared topic
 * @param replicas        replication factor of the declared topic (≥ 3 in production)
 */
@Validated
@ConfigurationProperties("app.messaging")
public record MessagingProperties(
        @NotBlank String source,
        @NotBlank String userEventsTopic,
        boolean createTopics,
        @Min(1) int partitions,
        @Min(1) short replicas) {
}
