package com.example.order.service;

import static com.example.order.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.order.TestData;
import com.example.order.dto.OrderCreateRequest;
import com.example.order.dto.OrderLineRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.exception.OrderNotFoundException;
import com.example.order.exception.UserCannotOrderException;
import com.example.order.messaging.publisher.OrderEventPublisher;
import com.example.order.model.CancellationReason;
import com.example.order.model.KnownUser;
import com.example.order.model.Order;
import com.example.order.model.OrderStatus;
import com.example.order.repository.KnownUserRepository;
import com.example.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orders;

    @Mock
    private KnownUserRepository knownUsers;

    @Mock
    private OrderEventPublisher events;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orders, knownUsers, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void place_activeUser_savesOrderAndPublishesOrderPlaced() {
        when(knownUsers.findForOrderPlacement(42L)).thenReturn(Optional.of(TestData.activeUser(42L, 0L)));
        when(orders.saveAndFlush(any(Order.class))).thenAnswer(inv -> persisted(inv.getArgument(0), 1001L));

        OrderResponse response = service.place(new OrderCreateRequest(42L, "EUR", List.of(
                new OrderLineRequest("SKU-1", 2, new BigDecimal("10")),
                new OrderLineRequest("SKU-2", 1, new BigDecimal("5.5")))));

        assertThat(response.id()).isEqualTo(1001L);
        assertThat(response.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(response.totalAmount()).hasToString("25.50");
        assertThat(response.placedAt()).isEqualTo(NOW);
        assertThat(response.lines()).hasSize(2);
        ArgumentCaptor<Order> published = ArgumentCaptor.forClass(Order.class);
        verify(events).placed(published.capture());
        assertThat(published.getValue().getId()).isEqualTo(1001L);
    }

    @Test
    void place_unknownUser_throws422Reason_andWritesNothing() {
        when(knownUsers.findForOrderPlacement(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.place(request(42L)))
                .isInstanceOfSatisfying(UserCannotOrderException.class, ex ->
                        assertThat(ex.getReason()).isEqualTo(UserCannotOrderException.Reason.USER_UNKNOWN));

        verify(orders, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void place_deactivatedUser_throws422Reason_andWritesNothing() {
        KnownUser user = TestData.activeUser(42L, 0L);
        user.applyDeactivation(1L);
        when(knownUsers.findForOrderPlacement(42L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.place(request(42L)))
                .isInstanceOfSatisfying(UserCannotOrderException.class, ex -> {
                    assertThat(ex.getReason()).isEqualTo(UserCannotOrderException.Reason.USER_NOT_ACTIVE);
                    assertThat(ex.getUserId()).isEqualTo(42L);
                });

        verify(orders, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void place_unvalidatedCurrency_failsFast() {
        when(knownUsers.findForOrderPlacement(42L)).thenReturn(Optional.of(TestData.activeUser(42L, 0L)));

        assertThatThrownBy(() -> service.place(new OrderCreateRequest(42L, "XXX",
                List.of(new OrderLineRequest("A", 1, BigDecimal.ONE)))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(events);
    }

    @Test
    void cancel_placedOrder_publishesOrderCancelled_once() {
        Order order = TestData.order(7L, 42L, 0L);
        when(orders.findById(7L)).thenReturn(Optional.of(order));
        when(orders.saveAndFlush(order)).thenReturn(order);

        OrderResponse first = service.cancel(7L);
        OrderResponse second = service.cancel(7L);

        assertThat(first.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(first.cancellationReason()).isEqualTo(CancellationReason.CUSTOMER_REQUEST);
        assertThat(second.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(events, times(1)).cancelled(order, null);
    }

    @Test
    void cancel_unknownOrder_throwsNotFound() {
        when(orders.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(9L))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessage("Order 9 not found");
    }

    @Test
    void findById_returnsOrder_orThrowsNotFound() {
        when(orders.findById(7L)).thenReturn(Optional.of(TestData.order(7L, 42L, 0L)));
        when(orders.findById(8L)).thenReturn(Optional.empty());

        assertThat(service.findById(7L).userId()).isEqualTo(42L);
        assertThatThrownBy(() -> service.findById(8L)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void findAll_usesTheQueryMatchingTheFilters() {
        Pageable pageable = PageRequest.of(0, 10);
        var page = new PageImpl<>(List.of(TestData.order(1L, 42L, 0L)), pageable, 1);
        when(orders.findAll(pageable)).thenReturn(page);
        when(orders.findByUserId(42L, pageable)).thenReturn(page);
        when(orders.findByStatus(OrderStatus.PLACED, pageable)).thenReturn(page);
        when(orders.findByUserIdAndStatus(42L, OrderStatus.CANCELLED, pageable)).thenReturn(new PageImpl<>(List.of()));

        assertThat(service.findAll(null, null, pageable).getContent()).hasSize(1);
        assertThat(service.findAll(42L, null, pageable).getContent()).hasSize(1);
        assertThat(service.findAll(null, OrderStatus.PLACED, pageable).getContent()).hasSize(1);
        assertThat(service.findAll(42L, OrderStatus.CANCELLED, pageable).getContent()).isEmpty();
    }

    private static OrderCreateRequest request(long userId) {
        return new OrderCreateRequest(userId, "EUR", List.of(new OrderLineRequest("SKU-1", 1, BigDecimal.TEN)));
    }

    private static Order persisted(Order order, long id) {
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "version", 0L);
        return order;
    }
}
