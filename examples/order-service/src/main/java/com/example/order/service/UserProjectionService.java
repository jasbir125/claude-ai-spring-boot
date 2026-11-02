package com.example.order.service;

import com.example.order.messaging.event.user.UserDeactivated;
import com.example.order.messaging.event.user.UserEvent;
import com.example.order.messaging.event.user.UserRegistered;
import com.example.order.messaging.event.user.UserUpdated;
import com.example.order.messaging.publisher.OrderEventPublisher;
import com.example.order.model.CancellationReason;
import com.example.order.model.KnownUser;
import com.example.order.model.Order;
import com.example.order.model.OrderStatus;
import com.example.order.repository.KnownUserRepository;
import com.example.order.repository.OrderRepository;
import com.example.order.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies user-service events to the {@code known_user} projection and enforces "a deactivated user has no open
 * orders". Idempotent twice over: the inbox drops redelivered event IDs, and the user version drops stale events.
 * Inbox row, projection change, cancellations and their outbox events commit in one transaction.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class UserProjectionService {

    static final String HANDLER = "known-user-projection";

    private final ProcessedEventRepository inbox;
    private final KnownUserRepository knownUsers;
    private final OrderRepository orders;
    private final OrderEventPublisher events;
    private final Clock clock;
    private final MeterRegistry meters;

    /** @return what happened, for logs and tests */
    @Transactional
    public Outcome apply(UUID eventId, UserEvent event) {
        if (!inbox.markProcessed(eventId, HANDLER)) {
            meters.counter("user.events.duplicates").increment();
            log.info("Duplicate user event skipped eventId={} userId={}", eventId, event.userId());
            return Outcome.DUPLICATE;
        }
        Outcome outcome = switch (event) {
            case UserRegistered registered -> register(registered);
            case UserUpdated updated -> update(updated);
            case UserDeactivated deactivated -> deactivate(eventId, deactivated);
        };
        meters.counter("user.events.applied", "type", event.getClass().getSimpleName(),
                "outcome", outcome.name().toLowerCase()).increment();
        log.info("User event processed eventId={} type={} userId={} version={} outcome={}", eventId,
                event.getClass().getSimpleName(), event.userId(), event.version(), outcome);
        return outcome;
    }

    private Outcome register(UserRegistered event) {
        if (knownUsers.findForUpdate(event.userId()).isPresent()) {
            return Outcome.STALE;   // already known: a registration is never newer than what we have
        }
        knownUsers.save(KnownUser.registered(event.userId(), event.version()));
        return Outcome.APPLIED;
    }

    /**
     * Fail closed: an update for a user we never saw does not create one, because the event does not say whether
     * the user is active. Such a user can order only after a replay or backfill (see README, operations).
     */
    private Outcome update(UserUpdated event) {
        Optional<KnownUser> user = knownUsers.findForUpdate(event.userId());
        if (user.isEmpty()) {
            log.warn("UserUpdated for unknown user ignored userId={} version={}", event.userId(), event.version());
            return Outcome.UNKNOWN_USER;
        }
        return user.get().applyUpdate(event.version()) ? Outcome.APPLIED : Outcome.STALE;
    }

    private Outcome deactivate(UUID eventId, UserDeactivated event) {
        Optional<KnownUser> existing = knownUsers.findForUpdate(event.userId());
        if (existing.isEmpty()) {
            knownUsers.save(KnownUser.deactivated(event.userId(), event.version()));
            return Outcome.APPLIED;   // never known as active, so there are no orders to cancel
        }
        if (!existing.get().applyDeactivation(event.version())) {
            return Outcome.STALE;
        }
        // the exclusive lock above waited for running order placements, so their orders are visible here
        List<Order> open = orders.findByUserIdAndStatusOrderById(event.userId(), OrderStatus.PLACED);
        for (Order order : open) {
            order.cancel(CancellationReason.USER_DEACTIVATED, clock.instant());
            events.cancelled(orders.saveAndFlush(order), eventId);
        }
        if (!open.isEmpty()) {
            log.info("Cancelled {} open orders of deactivated user userId={}", open.size(), event.userId());
        }
        return Outcome.APPLIED;
    }

    public enum Outcome {
        APPLIED,
        /** Older than or equal to the known user version. */
        STALE,
        /** Redelivered event ID. */
        DUPLICATE,
        /** {@code UserUpdated} for a user that was never registered here. */
        UNKNOWN_USER
    }
}
