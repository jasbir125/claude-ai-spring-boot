package com.example.user.messaging.event;

import java.time.Instant;

/** A user account was created. Carries email and display name for consumers such as notifications. */
public record UserRegistered(long userId, String email, String displayName, Instant registeredAt, long version)
        implements UserEvent {

    @Override
    public String eventType() {
        return "UserRegistered";
    }

    @Override
    public int schemaVersion() {
        return 1;
    }
}
