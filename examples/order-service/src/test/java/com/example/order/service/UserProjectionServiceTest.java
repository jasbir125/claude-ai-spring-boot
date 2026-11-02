package com.example.order.service;

import static com.example.order.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.order.TestData;
import com.example.order.messaging.event.user.UserDeactivated;
import com.example.order.messaging.event.user.UserRegistered;
import com.example.order.messaging.event.user.UserUpdated;
import com.example.order.messaging.publisher.OrderEventPublisher;
import com.example.order.model.CancellationReason;
import com.example.order.model.KnownUser;
import com.example.order.model.KnownUserStatus;
import com.example.order.model.Order;
import com.example.order.model.OrderStatus;
import com.example.order.repository.KnownUserRepository;
import com.example.order.repository.OrderRepository;
import com.example.order.repository.ProcessedEventRepository;
import com.example.order.service.UserProjectionService.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserProjectionServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    @Mock
    private ProcessedEventRepository inbox;

    @Mock
    private KnownUserRepository knownUsers;

    @Mock
    private OrderRepository orders;

    @Mock
    private OrderEventPublisher events;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private UserProjectionService service;

    @BeforeEach
    void setUp() {
        service = new UserProjectionService(inbox, knownUsers, orders, events, Clock.fixed(NOW, ZoneOffset.UTC),
                meters);
    }

    @Test
    void duplicateEventId_isSkipped_withoutTouchingTheProjection() {
        when(inbox.markProcessed(EVENT_ID, UserProjectionService.HANDLER)).thenReturn(false);

        assertThat(service.apply(EVENT_ID, new UserDeactivated(42L, NOW, 2L))).isEqualTo(Outcome.DUPLICATE);

        verifyNoInteractions(knownUsers, orders, events);
        assertThat(meters.counter("user.events.duplicates").count()).isEqualTo(1.0);
    }

    @Test
    void userRegistered_newUser_isStoredActive() {
        firstDelivery();
        when(knownUsers.findForUpdate(42L)).thenReturn(Optional.empty());

        assertThat(service.apply(EVENT_ID, new UserRegistered(42L, 0L))).isEqualTo(Outcome.APPLIED);

        ArgumentCaptor<KnownUser> saved = ArgumentCaptor.forClass(KnownUser.class);
        verify(knownUsers).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(42L);
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(meters.counter("user.events.applied", "type", "UserRegistered", "outcome", "applied").count())
                .isEqualTo(1.0);
    }

    @Test
    void userRegistered_alreadyKnown_isStale() {
        firstDelivery();
        when(knownUsers.findForUpdate(42L)).thenReturn(Optional.of(TestData.activeUser(42L, 3L)));

        assertThat(service.apply(EVENT_ID, new UserRegistered(42L, 0L))).isEqualTo(Outcome.STALE);
        verify(knownUsers, never()).save(any());
    }

    @Test
    void userUpdated_newerVersion_isApplied_olderIsStale() {
        firstDelivery();
        KnownUser user = TestData.activeUser(42L, 1L);
        when(knownUsers.findForUpdate(42L)).thenReturn(Optional.of(user));

        assertThat(service.apply(EVENT_ID, new UserUpdated(42L, 2L))).isEqualTo(Outcome.APPLIED);
        assertThat(service.apply(EVENT_ID, new UserUpdated(42L, 2L))).isEqualTo(Outcome.STALE);
        assertThat(user.getUserVersion()).isEqualTo(2L);
    }

    @Test
    void userUpdated_unknownUser_failsClosed_andCreatesNothing() {
        firstDelivery();
        when(knownUsers.findForUpdate(42L)).thenReturn(Optional.empty());

        assertThat(service.apply(EVENT_ID, new UserUpdated(42L, 1L))).isEqualTo(Outcome.UNKNOWN_USER);
        verify(knownUsers, never()).save(any());
    }

    @Test
    void userDeactivated_cancelsOpenOrders_withCausation() {
        firstDelivery();
        KnownUser user = TestData.activeUser(42L, 1L);
        Order first = TestData.order(1L, 42L, 0L);
        Order second = TestData.order(2L, 42L, 0L);
        when(knownUsers.findForUpdate(42L)).thenReturn(Optional.of(user));
        when(orders.findByUserIdAndStatusOrderById(42L, OrderStatus.PLACED)).thenReturn(List.of(first, second));
        when(orders.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.apply(EVENT_ID, new UserDeactivated(42L, NOW, 2L))).isEqualTo(Outcome.APPLIED);

        assertThat(user.getStatus()).isEqualTo(KnownUserStatus.DEACTIVATED);
        assertThat(List.of(first, second)).allSatisfy(order -> {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getCancellationReason()).isEqualTo(CancellationReason.USER_DEACTIVATED);
            assertThat(order.getCancelledAt()).isEqualTo(NOW);
        });
        verify(events).cancelled(first, EVENT_ID);
        verify(events).cancelled(second, EVENT_ID);
    }

    @Test
    void userDeactivated_withoutOpenOrders_publishesNothing() {
        firstDelivery();
        when(knownUsers.findForUpdate(42L)).thenReturn(Optional.of(TestData.activeUser(42L, 1L)));
        when(orders.findByUserIdAndStatusOrderById(42L, OrderStatus.PLACED)).thenReturn(List.of());

        assertThat(service.apply(EVENT_ID, new UserDeactivated(42L, NOW, 2L))).isEqualTo(Outcome.APPLIED);
        verifyNoInteractions(events);
    }

    @Test
    void userDeactivated_staleVersion_cancelsNothing() {
        firstDelivery();
        KnownUser user = TestData.activeUser(42L, 5L);
        when(knownUsers.findForUpdate(42L)).thenReturn(Optional.of(user));

        assertThat(service.apply(EVENT_ID, new UserDeactivated(42L, NOW, 2L))).isEqualTo(Outcome.STALE);

        assertThat(user.isActive()).isTrue();
        verifyNoInteractions(orders, events);
    }

    @Test
    void userDeactivated_unknownUser_isStoredDeactivated() {
        firstDelivery();
        when(knownUsers.findForUpdate(42L)).thenReturn(Optional.empty());

        assertThat(service.apply(EVENT_ID, new UserDeactivated(42L, NOW, 2L))).isEqualTo(Outcome.APPLIED);

        ArgumentCaptor<KnownUser> saved = ArgumentCaptor.forClass(KnownUser.class);
        verify(knownUsers).save(saved.capture());
        assertThat(saved.getValue().isActive()).isFalse();
        verifyNoInteractions(orders, events);
    }

    private void firstDelivery() {
        when(inbox.markProcessed(EVENT_ID, UserProjectionService.HANDLER)).thenReturn(true);
    }
}
