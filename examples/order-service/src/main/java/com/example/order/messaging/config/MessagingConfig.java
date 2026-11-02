package com.example.order.messaging.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Declares only the topics this service owns. {@code user.user.events.v1} belongs to user-service and is never
 * declared here.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({MessagingProperties.class, OutboxProperties.class, ConsumerProperties.class})
public class MessagingConfig {

    /** Local/dev only: shared environments create topics with Strimzi/Terraform and disable this. */
    @Bean
    @ConditionalOnProperty(prefix = "app.messaging", name = "create-topics", havingValue = "true")
    NewTopic orderEventsTopic(MessagingProperties props) {
        return TopicBuilder.name(props.orderEventsTopic())
                .partitions(props.partitions())
                .replicas(props.replicas())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.messaging", name = "create-topics", havingValue = "true")
    NewTopic userEventsDltTopic(MessagingProperties props) {
        return TopicBuilder.name(props.userEventsDltTopic())
                .partitions(props.partitions())
                .replicas(props.replicas())
                .build();
    }
}
