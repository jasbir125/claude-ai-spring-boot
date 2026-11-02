package com.example.order.messaging.event.user;

/**
 * Events consumed from {@code user.user.events.v1}, owned by user-service (contract: user-service docs/events.md).
 * Tolerant reader: only the fields order-service needs are mapped; email and display name are deliberately not
 * read, so no personal data enters this service.
 */
public sealed interface UserEvent permits UserRegistered, UserUpdated, UserDeactivated {

    long userId();

    /** The user's version in user-service after the change; used to drop stale and duplicate events. */
    long version();
}
