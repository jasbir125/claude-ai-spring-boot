package com.example.order.messaging.config;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Producer value serializer: outbox envelopes are JSON strings; the dead-letter recoverer re-publishes the raw
 * {@code byte[]} of a record that could not be deserialized. Anything else is a programming error.
 */
public class StringOrBytesSerializer implements Serializer<Object> {

    @Override
    public byte[] serialize(String topic, Object data) {
        return switch (data) {
            case null -> null;
            case String text -> text.getBytes(StandardCharsets.UTF_8);
            case byte[] bytes -> bytes;
            default -> throw new IllegalArgumentException("Unsupported value type " + data.getClass().getName());
        };
    }
}
