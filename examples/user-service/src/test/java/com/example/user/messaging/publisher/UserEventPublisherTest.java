package com.example.user.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.example.user.messaging.config.MessagingProperties;
import com.example.user.messaging.event.UserDeactivated;
import com.example.user.messaging.event.UserRegistered;
import com.example.user.messaging.event.UserUpdated;
import com.example.user.model.User;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserEventPublisherTest {

    private static final String TOPIC = "user.user.events.v1";
    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    @Mock
    private OutboxWriter outbox;

    private UserEventPublisher publisher;
    private User user;

    @BeforeEach
    void setUp() {
        var props = new MessagingProperties("user-service", TOPIC, false, 1, (short) 1);
        publisher = new UserEventPublisher(outbox, props, Clock.fixed(NOW, ZoneOffset.UTC));
        user = User.register("ada@example.com", "Ada");
        ReflectionTestUtils.setField(user, "id", 42L);
        ReflectionTestUtils.setField(user, "version", 3L);
        ReflectionTestUtils.setField(user, "createdAt", NOW);
    }

    @Test
    void registered_appendsUserRegistered_keyedByUserId() {
        publisher.registered(user);

        verify(outbox).append(TOPIC, "User", "42", "UserRegistered", 1,
                new UserRegistered(42L, "ada@example.com", "Ada", NOW, 3L));
    }

    @Test
    void updated_appendsFullNewState() {
        publisher.updated(user);

        verify(outbox).append(TOPIC, "User", "42", "UserUpdated", 1,
                new UserUpdated(42L, "ada@example.com", "Ada", 3L));
    }

    @Test
    void deactivated_appendsEventWithoutPersonalData() {
        publisher.deactivated(user);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq(TOPIC), eq("User"), eq("42"), eq("UserDeactivated"), eq(1), payload.capture());
        assertThat(payload.getValue()).isEqualTo(new UserDeactivated(42L, NOW, 3L));
        assertThat(payload.getValue().toString()).doesNotContain("ada@example.com");
    }
}
