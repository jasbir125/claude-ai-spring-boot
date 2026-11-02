package com.example.order.messaging.publisher;

import com.example.order.messaging.config.MessagingProperties;
import com.example.order.messaging.event.OrderCancelled;
import com.example.order.messaging.event.OrderEvent;
import com.example.order.messaging.event.OrderPlaced;
import com.example.order.model.Order;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Maps order state changes to versioned events and appends them to the outbox (same transaction as the change). */
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    static final String AGGREGATE_TYPE = "Order";

    private final OutboxWriter outbox;
    private final MessagingProperties props;

    public void placed(Order order) {
        var lines = order.getLines().stream()
                .map(l -> new OrderPlaced.Line(l.getLineNo(), l.getSku(), l.getQuantity(), l.getUnitPrice(),
                        l.getLineTotal()))
                .toList();
        append(new OrderPlaced(order.getId(), order.getUserId(), order.getCurrency(), order.getTotalAmount(), lines,
                order.getPlacedAt(), order.getVersion()), null);
    }

    /** @param causationId the consumed event that caused the cancellation, {@code null} for an API cancellation */
    public void cancelled(Order order, UUID causationId) {
        append(new OrderCancelled(order.getId(), order.getUserId(), order.getCancellationReason().name(),
                order.getCancelledAt(), order.getVersion()), causationId);
    }

    private void append(OrderEvent event, UUID causationId) {
        outbox.append(props.orderEventsTopic(), AGGREGATE_TYPE, Long.toString(event.orderId()), event.eventType(),
                event.schemaVersion(), event, causationId);
    }
}
