package com.example.user.messaging.event;

/** Email or display name of a user changed; carries the full new state (event-carried state transfer). */
public record UserUpdated(long userId, String email, String displayName, long version) implements UserEvent {

    @Override
    public String eventType() {
        return "UserUpdated";
    }

    @Override
    public int schemaVersion() {
        return 1;
    }
}
