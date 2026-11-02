package com.example.user.messaging.config;

import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({MessagingProperties.class, OutboxProperties.class})
public class MessagingConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Local/dev only: shared environments create topics with Strimzi/Terraform and disable this. */
    @Bean
    @ConditionalOnProperty(prefix = "app.messaging", name = "create-topics", havingValue = "true")
    NewTopic userEventsTopic(MessagingProperties props) {
        return TopicBuilder.name(props.userEventsTopic())
                .partitions(props.partitions())
                .replicas(props.replicas())
                .build();
    }
}
