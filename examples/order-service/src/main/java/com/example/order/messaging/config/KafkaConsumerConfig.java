package com.example.order.messaging.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Bounded blocking retries, then the dead-letter topic. Blocking retries keep the per-key order, which the user
 * projection relies on. Deserialization and {@link NonRetryableEventException} go to the DLT immediately. Spring
 * Boot wires this {@code CommonErrorHandler} into the auto-configured listener container factory.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class KafkaConsumerConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template, MessagingProperties messaging,
                                          ConsumerProperties consumer, MeterRegistry meters) {
        // partition -1: let the key choose the DLT partition, so the DLT may have any number of partitions
        var deadLetter = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(messaging.userEventsDltTopic(), -1));
        Counter deadLettered = Counter.builder("user.events.dead.lettered")
                .description("User events sent to the dead-letter topic").register(meters);

        var backOff = new ExponentialBackOffWithMaxRetries(consumer.maxRetries());
        backOff.setInitialInterval(consumer.initialInterval().toMillis());
        backOff.setMultiplier(consumer.multiplier());
        backOff.setMaxInterval(consumer.maxInterval().toMillis());

        var handler = new DefaultErrorHandler((record, ex) -> {
            // no payload in logs: it may contain personal data
            log.error("User event dead-lettered topic={} partition={} offset={} key={} cause={}", record.topic(),
                    record.partition(), record.offset(), record.key(), rootCauseName(ex));
            deadLetter.accept(record, ex);
            deadLettered.increment();
        }, backOff);
        handler.addNotRetryableExceptions(NonRetryableEventException.class);
        return handler;
    }

    private static String rootCauseName(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
