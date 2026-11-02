package com.example.order.messaging.event.user;

/** {@code UserRegistered} v1 (only the fields order-service uses). */
public record UserRegistered(long userId, long version) implements UserEvent {

    public static final String TYPE = "UserRegistered";
}
