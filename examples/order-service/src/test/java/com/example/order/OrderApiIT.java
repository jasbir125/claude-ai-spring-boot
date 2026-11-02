package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.example.order.config.Correlation;
import com.example.order.dto.OrderCreateRequest;
import com.example.order.dto.OrderLineRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.messaging.publisher.OutboxEventRepository;
import com.example.order.messaging.publisher.OutboxWriter;
import com.example.order.model.CancellationReason;
import com.example.order.model.OrderStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.JsonNode;

/** End to end over HTTP: request → service → order + outbox row (one transaction) → relay → order events topic. */
class OrderApiIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private Flyway flyway;

    @Test
    void place_activeUser_returns201_andPublishesOrderPlacedThroughOutbox() {
        long userId = registeredUser();
        String correlationId = "it-" + UUID.randomUUID();

        OrderResponse created = client.post().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .header(Correlation.HEADER, correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(orderRequest(userId))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueMatches(HttpHeaders.LOCATION, ".*/api/v1/orders/\\d+")
                .expectHeader().valueEquals(Correlation.HEADER, correlationId)
                .expectBody(OrderResponse.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(created.userId()).isEqualTo(userId);
        assertThat(created.totalAmount()).isEqualByComparingTo("25.50");
        assertThat(created.lines()).extracting(l -> l.lineTotal().toPlainString()).containsExactly("20.00", "5.50");
        assertThat(created.version()).isZero();

        ConsumerRecord<String, String> record = awaitOrderEvents(created.id(), 1).getFirst();
        JsonNode envelope = jsonMapper.readTree(record.value());
        assertThat(record.key()).isEqualTo(created.id().toString());
        assertThat(header(record, "eventType")).isEqualTo("OrderPlaced");
        assertThat(header(record, "eventId")).isEqualTo(envelope.get("eventId").asString());
        assertThat(envelope.get("source").asString()).isEqualTo("order-service");
        assertThat(envelope.get("correlationId").asString()).isEqualTo(correlationId);
        assertThat(envelope.get("causationId").isNull()).isTrue();
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("userId").asLong()).isEqualTo(userId);
        assertThat(payload.get("currency").asString()).isEqualTo("EUR");
        // contract: amounts are decimal strings at the currency's scale
        assertThat(payload.get("totalAmount").isString()).isTrue();
        assertThat(payload.get("totalAmount").asString()).isEqualTo("25.50");
        assertThat(payload.get("lines")).hasSize(2);
        assertThat(payload.get("lines").get(1).get("unitPrice").asString()).isEqualTo("5.50");

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(outbox.findByAggregateIdOrderByOccurredAt(created.id().toString()))
                        .singleElement()
                        .satisfies(row -> assertThat(row.getPublishedAt()).isNotNull()));
    }

    @Test
    void place_storesAmountsAtTheCurrencyScale_afterTheDatabaseRoundTrip() {
        long userId = registeredUser();
        var yen = new OrderCreateRequest(userId, "JPY", List.of(new OrderLineRequest("SKU-JP", 3, new BigDecimal("400"))));

        OrderResponse created = client.post().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(yen)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class).returnResult().getResponseBody();
        assertThat(created).isNotNull();

        // NUMERIC(19,4) in the database, but callers always see the currency's own scale (JPY: 0 decimals)
        OrderResponse reloaded = getOrder(created.id());
        assertThat(reloaded.totalAmount().toPlainString()).isEqualTo("1200");
        assertThat(reloaded.lines().getFirst().unitPrice().toPlainString()).isEqualTo("400");
    }

    @Test
    void place_unknownUser_returns422_andWritesNoEvent() {
        long unknown = newUserId();

        client.post().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(orderRequest(unknown))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("User cannot place orders")
                .jsonPath("$.reason").isEqualTo("USER_UNKNOWN");

        // no order, hence no OrderPlaced in the outbox (events are only written together with an order change)
        client.get().uri("/api/v1/orders?userId={u}", unknown)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + READER)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.page.totalElements").isEqualTo(0);
    }

    @Test
    void place_tooManyDecimalsForCurrency_returns400() {
        long userId = registeredUser();
        var request = new OrderCreateRequest(userId, "JPY", List.of(new OrderLineRequest("SKU-1", 1, new BigDecimal("1.5"))));

        client.post().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("lines[0].unitPrice")
                .jsonPath("$.errors[0].code").isEqualTo("scale");
    }

    @Test
    void cancel_isIdempotent_andPublishesOrderCancelledOnce_afterOrderPlaced() {
        long userId = registeredUser();
        OrderResponse placed = placeOrder(userId);

        OrderResponse cancelled = cancel(placed.id());
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancellationReason()).isEqualTo(CancellationReason.CUSTOMER_REQUEST);
        assertThat(cancelled.version()).isEqualTo(1L);

        OrderResponse again = cancel(placed.id());   // no second event, no version change
        assertThat(again.version()).isEqualTo(1L);
        assertThat(again.cancelledAt()).isEqualTo(cancelled.cancelledAt());

        List<ConsumerRecord<String, String>> records = awaitOrderEvents(placed.id(), 2);
        assertThat(records).extracting(r -> header(r, "eventType")).containsExactly("OrderPlaced", "OrderCancelled");
        JsonNode cancelledPayload = jsonMapper.readTree(records.get(1).value()).get("payload");
        assertThat(cancelledPayload.get("reason").asString()).isEqualTo("CUSTOMER_REQUEST");
        assertThat(cancelledPayload.get("version").asLong()).isEqualTo(1L);
        assertThat(outbox.findByAggregateIdOrderByOccurredAt(placed.id().toString())).hasSize(2);
    }

    @Test
    void cancel_unknownOrder_returns404() {
        client.post().uri("/api/v1/orders/{id}/cancel", Long.MAX_VALUE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.title").isEqualTo("Order not found");
    }

    @Test
    void list_filtersByUserAndStatus_andRejectsUnknownSortProperty() {
        long userId = registeredUser();
        OrderResponse open = placeOrder(userId);
        OrderResponse closed = placeOrder(userId);
        cancel(closed.id());

        String body = client.get().uri("/api/v1/orders?userId={u}&status=PLACED&size=100", userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + READER)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        JsonNode page = jsonMapper.readTree(body);
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(o -> ids.add(o.get("id").asLong()));
        assertThat(ids).containsExactly(open.id());
        assertThat(page.get("page").get("totalElements").asLong()).isEqualTo(1L);

        client.get().uri("/api/v1/orders?sort=secret")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + READER)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo("Unknown sort property 'secret'");
    }

    @Test
    void security_missingOrInvalidToken_returns401_readerCannotWrite_returns403() {
        client.get().uri("/api/v1/orders").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer forged")
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + READER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(orderRequest(1L))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void probes_arePublic_andUp() {
        client.get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
    }

    @Test
    void flyway_allMigrationsApplied() {
        assertThat(flyway.info().applied()).hasSize(2);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void outboxWriter_outsideTransaction_isRejected() {
        assertThatThrownBy(() -> outboxWriter.append(orderEventsTopic, "Order", "1", "OrderPlaced", 1, Map.of(), null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private OrderResponse cancel(long id) {
        OrderResponse response = client.post().uri("/api/v1/orders/{id}/cancel", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private List<ConsumerRecord<String, String>> awaitOrderEvents(long orderId, int expected) {
        List<ConsumerRecord<String, String>> records =
                awaitRecords(orderEventsTopic, r -> Long.toString(orderId).equals(r.key()), expected);
        assertThat(records).hasSize(expected);
        return records;
    }
}
