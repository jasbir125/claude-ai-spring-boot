package com.example.order.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.example.order.TestData;
import com.example.order.messaging.config.MessagingProperties;
import com.example.order.messaging.event.OrderCancelled;
import com.example.order.messaging.event.OrderPlaced;
import com.example.order.model.CancellationReason;
import com.example.order.model.Order;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    private static final String TOPIC = "order.order.events.v1";

    @Mock
    private OutboxWriter outbox;

    private OrderEventPublisher publisher;

    @BeforeEach
    void setUp() {
        var props = new MessagingProperties("order-service", TOPIC, "user.user.events.v1", "dlt", false, 1, (short) 1);
        publisher = new OrderEventPublisher(outbox, props);
    }

    @Test
    void placed_appendsCompleteOrder_keyedByOrderId() {
        Order order = TestData.order(1001L, 42L, 0L);

        publisher.placed(order);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq(TOPIC), eq("Order"), eq("1001"), eq("OrderPlaced"), eq(1), payload.capture(),
                eq(null));
        OrderPlaced event = (OrderPlaced) payload.getValue();
        assertThat(event.userId()).isEqualTo(42L);
        assertThat(event.currency()).isEqualTo("EUR");
        assertThat(event.totalAmount()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(event.lines()).containsExactly(
                new OrderPlaced.Line(1, "SKU-1", 2, new BigDecimal("10.00"), new BigDecimal("20.00")));
    }

    @Test
    void cancelled_carriesReasonAndCausation() {
        Order order = TestData.order(1001L, 42L, 1L);
        order.cancel(CancellationReason.USER_DEACTIVATED, TestData.NOW);
        UUID cause = UUID.randomUUID();

        publisher.cancelled(order, cause);

        verify(outbox).append(TOPIC, "Order", "1001", "OrderCancelled", 1,
                new OrderCancelled(1001L, 42L, "USER_DEACTIVATED", TestData.NOW, 1L), cause);
    }

    @Test
    void orderPlacedLines_areImmutable() {
        List<OrderPlaced.Line> lines = new ArrayList<>();
        var event = new OrderPlaced(1L, 2L, "EUR", BigDecimal.ONE, lines, TestData.NOW, 0L);
        lines.add(new OrderPlaced.Line(1, "A", 1, BigDecimal.ONE, BigDecimal.ONE));

        assertThat(event.lines()).isEmpty();
    }
}
