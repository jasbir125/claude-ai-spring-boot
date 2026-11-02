package com.example.user.messaging.publisher;

import com.example.user.messaging.config.MessagingProperties;
import com.example.user.messaging.event.UserDeactivated;
import com.example.user.messaging.event.UserEvent;
import com.example.user.messaging.event.UserRegistered;
import com.example.user.messaging.event.UserUpdated;
import com.example.user.model.User;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Maps user state changes to versioned events and appends them to the outbox (same transaction as the change). */
@Component
@RequiredArgsConstructor
public class UserEventPublisher {

    static final String AGGREGATE_TYPE = "User";

    private final OutboxWriter outbox;
    private final MessagingProperties props;
    private final Clock clock;

    public void registered(User user) {
        append(new UserRegistered(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt(),
                user.getVersion()));
    }

    public void updated(User user) {
        append(new UserUpdated(user.getId(), user.getEmail(), user.getDisplayName(), user.getVersion()));
    }

    public void deactivated(User user) {
        append(new UserDeactivated(user.getId(), clock.instant(), user.getVersion()));
    }

    private void append(UserEvent event) {
        outbox.append(props.userEventsTopic(), AGGREGATE_TYPE, Long.toString(event.userId()), event.eventType(),
                event.schemaVersion(), event);
    }
}
