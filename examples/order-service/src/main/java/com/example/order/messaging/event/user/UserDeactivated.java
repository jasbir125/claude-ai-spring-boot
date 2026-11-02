package com.example.order.messaging.event.user;

import java.time.Instant;

/** {@code UserDeactivated} v1. */
public record UserDeactivated(long userId, Instant deactivatedAt, long version) implements UserEvent {

    public static final String TYPE = "UserDeactivated";
}
