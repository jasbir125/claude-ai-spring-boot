package com.example.order.messaging.event.user;

/** {@code UserUpdated} v1 (only the fields order-service uses). */
public record UserUpdated(long userId, long version) implements UserEvent {

    public static final String TYPE = "UserUpdated";
}
