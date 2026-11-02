package com.example.user.messaging.event;

/**
 * Domain events published on the user events topic. Payloads carry the aggregate {@code version} so consumers
 * can ignore stale or out-of-order events.
 */
public sealed interface UserEvent permits UserRegistered, UserUpdated, UserDeactivated {

    long userId();

    long version();

    /** Contract name, written to the envelope; not part of the payload. */
    String eventType();

    /** Major schema version of the payload; not part of the payload. */
    int schemaVersion();
}
