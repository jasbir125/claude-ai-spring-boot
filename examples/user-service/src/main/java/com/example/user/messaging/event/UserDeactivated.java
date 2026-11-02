package com.example.user.messaging.event;

import java.time.Instant;

/** A user was deactivated. No personal data: consumers react by user ID. */
public record UserDeactivated(long userId, Instant deactivatedAt, long version) implements UserEvent {

    @Override
    public String eventType() {
        return "UserDeactivated";
    }

    @Override
    public int schemaVersion() {
        return 1;
    }
}
