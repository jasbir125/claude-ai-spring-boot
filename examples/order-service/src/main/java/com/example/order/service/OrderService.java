package com.example.order.service;

import com.example.order.dto.OrderCreateRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.exception.OrderNotFoundException;
import com.example.order.exception.UserCannotOrderException;
import com.example.order.exception.UserCannotOrderException.Reason;
import com.example.order.messaging.publisher.OrderEventPublisher;
import com.example.order.model.CancellationReason;
import com.example.order.model.KnownUser;
import com.example.order.model.Money;
import com.example.order.model.NewOrderLine;
import com.example.order.model.Order;
import com.example.order.model.OrderStatus;
import com.example.order.repository.KnownUserRepository;
import com.example.order.repository.OrderRepository;
import java.time.Clock;
import java.util.Currency;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order use cases. Every state change and its event are written in one transaction (outbox); nothing here talks
 * to the broker or to user-service: who may order is decided from the local {@code known_user} projection.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orders;
    private final KnownUserRepository knownUsers;
    private final OrderEventPublisher events;
    private final Clock clock;

    public Page<OrderResponse> findAll(Long userId, OrderStatus status, Pageable pageable) {
        Page<Order> page;
        if (userId != null && status != null) {
            page = orders.findByUserIdAndStatus(userId, status, pageable);
        } else if (userId != null) {
            page = orders.findByUserId(userId, pageable);
        } else if (status != null) {
            page = orders.findByStatus(status, pageable);
        } else {
            page = orders.findAll(pageable);
        }
        return page.map(OrderResponse::from);
    }

    public OrderResponse findById(Long id) {
        return OrderResponse.from(load(id));
    }

    /**
     * Places an order for an ACTIVE known user. The user row is read with a shared lock, so a concurrent
     * {@code UserDeactivated} either waits for this order (and then cancels it) or wins and this call gets 422.
     *
     * @throws UserCannotOrderException when the user is unknown or not active
     */
    @Transactional
    public OrderResponse place(OrderCreateRequest request) {
        KnownUser user = knownUsers.findForOrderPlacement(request.userId())
                .orElseThrow(() -> new UserCannotOrderException(request.userId(), Reason.USER_UNKNOWN));
        if (!user.isActive()) {
            throw new UserCannotOrderException(request.userId(), Reason.USER_NOT_ACTIVE);
        }
        Currency currency = Money.currency(request.currency())
                .orElseThrow(() -> new IllegalArgumentException("currency was not validated"));
        List<NewOrderLine> lines = request.lines().stream()
                .map(l -> new NewOrderLine(l.sku(), l.quantity(), l.unitPrice()))
                .toList();
        // flush: id and version are assigned before they go into the event
        Order order = orders.saveAndFlush(Order.place(user.getUserId(), currency, lines, clock.instant()));
        events.placed(order);
        log.info("Order placed orderId={} userId={} lines={}", order.getId(), order.getUserId(), lines.size());
        return OrderResponse.from(order);
    }

    /** Idempotent: cancelling a cancelled order changes nothing and publishes no event. */
    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = load(id);
        if (order.cancel(CancellationReason.CUSTOMER_REQUEST, clock.instant())) {
            order = orders.saveAndFlush(order);   // flush increments the version before it is published
            events.cancelled(order, null);
            log.info("Order cancelled orderId={} reason={}", id, CancellationReason.CUSTOMER_REQUEST);
        }
        return OrderResponse.from(order);
    }

    private Order load(Long id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }
}
