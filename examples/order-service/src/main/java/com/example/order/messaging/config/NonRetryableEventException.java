package com.example.order.messaging.config;

/**
 * The record can never be processed (malformed envelope or payload). The error handler sends it to the
 * dead-letter topic without retrying. The message must not contain payload data: it is copied into DLT headers.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }
}
