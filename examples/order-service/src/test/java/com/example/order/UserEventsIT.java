package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.order.dto.OrderResponse;
import com.example.order.messaging.publisher.OutboxEvent;
import com.example.order.messaging.publisher.OutboxEventRepository;
import com.example.order.model.CancellationReason;
import com.example.order.model.KnownUser;
import com.example.order.model.KnownUserStatus;
import com.example.order.model.OrderStatus;
import com.example.order.repository.ProcessedEventRepository;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * The consumer side against real Kafka and PostgreSQL: user-service events (published here with a plain producer)
 * → {@code UserEventsListener} → inbox + {@code known_user} projection → order cancellations through the outbox.
 */
class UserEventsIT extends AbstractIntegrationTest {

    @Autowired
    private ProcessedEventRepository inbox;

    @Autowired
    private OutboxEventRepository outbox;

    @Test
    void registeredThenUpdated_buildsTheProjection_withoutPersonalData() {
        long userId = registeredUser();
        publishUserEvent("UserUpdated", userId, 1, "it-update");

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(knownUsers.findById(userId))
                .get().extracting(KnownUser::getUserVersion, KnownUser::getStatus)
                .containsExactly(1L, KnownUserStatus.ACTIVE));
    }

    @Test
    void userUpdated_forUnknownUser_failsClosed_userCannotOrder() {
        long userId = newUserId();
        UUID eventId = publishUserEvent("UserUpdated", userId, 3, "it-unknown");

        await().atMost(TIMEOUT).until(() -> inbox.count(eventId) == 1);
        assertThat(knownUsers.findById(userId)).isEmpty();
        expectCannotOrder(userId, "USER_UNKNOWN");
    }

    @Test
    void userDeactivated_cancelsOpenOrders_withCausation_andBlocksNewOrders() {
        long userId = registeredUser();
        OrderResponse open1 = placeOrder(userId);
        OrderResponse open2 = placeOrder(userId);
        OrderResponse cancelledByCustomer = placeOrder(userId);
        client.post().uri("/api/v1/orders/{id}/cancel", cancelledByCustomer.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .exchange().expectStatus().isOk();

        UUID deactivation = publishUserEvent("UserDeactivated", userId, 2, "it-deactivate-" + userId);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(getOrder(open1.id()).status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(getOrder(open2.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        });
        assertThat(getOrder(open1.id()).cancellationReason()).isEqualTo(CancellationReason.USER_DEACTIVATED);
        assertThat(getOrder(cancelledByCustomer.id()).cancellationReason())
                .as("an already cancelled order keeps its first reason")
                .isEqualTo(CancellationReason.CUSTOMER_REQUEST);
        assertThat(knownUsers.findById(userId)).get().extracting(KnownUser::getStatus)
                .isEqualTo(KnownUserStatus.DEACTIVATED);

        // OrderCancelled is published through the outbox and links back to the consumed event
        ConsumerRecord<String, String> record = awaitRecords(orderEventsTopic,
                r -> open1.id().toString().equals(r.key()) && "OrderCancelled".equals(header(r, "eventType")), 1)
                .getFirst();
        JsonNode envelope = jsonMapper.readTree(record.value());
        assertThat(envelope.get("causationId").asString()).isEqualTo(deactivation.toString());
        assertThat(envelope.get("correlationId").asString()).isEqualTo("it-deactivate-" + userId);
        assertThat(envelope.get("payload").get("reason").asString()).isEqualTo("USER_DEACTIVATED");

        expectCannotOrder(userId, "USER_NOT_ACTIVE");
    }

    @Test
    void duplicateDelivery_andReplayWithNewEventId_cancelOrdersOnlyOnce() {
        long userId = registeredUser();
        OrderResponse order = placeOrder(userId);
        UUID deactivation = UUID.randomUUID();

        publishUserEvent(deactivation, "UserDeactivated", userId, 2, "it-dup");
        publishUserEvent(deactivation, "UserDeactivated", userId, 2, "it-dup");   // redelivery: same eventId
        publishUserEvent("UserDeactivated", userId, 2, "it-replay");               // replay: new eventId, same version
        // same key → same partition: once this one is applied, all three before it were processed
        publishUserEvent("UserUpdated", userId, 3, "it-marker");

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(knownUsers.findById(userId)).get().extracting(KnownUser::getUserVersion).isEqualTo(3L));

        assertThat(inbox.count(deactivation)).as("inbox holds the duplicate eventId once").isEqualTo(1L);
        assertThat(outbox.findByAggregateIdOrderByOccurredAt(order.id().toString()))
                .extracting(OutboxEvent::getEventType)
                .containsExactly("OrderPlaced", "OrderCancelled");
        assertThat(getOrder(order.id()).version()).isEqualTo(1L);
    }

    @Test
    void poisonMessages_goToTheDeadLetterTopic_withoutBlockingThePartition() {
        long userId = newUserId();
        String key = Long.toString(userId);
        publishRaw(key, "{not json");
        publishRaw(key, """
                {"eventId": "%s", "eventType": "UserRegistered", "schemaVersion": 1,
                 "payload": {"version": 0}}
                """.formatted(UUID.randomUUID()));
        // a valid event behind the poison ones on the same partition is still processed
        publishUserEvent("UserRegistered", userId, 0, "it-after-poison");

        await().atMost(TIMEOUT).until(() -> knownUsers.findById(userId).isPresent());

        List<ConsumerRecord<String, String>> dead = awaitRecords(userEventsDltTopic, r -> key.equals(r.key()), 2);
        assertThat(dead).hasSize(2);
        assertThat(dead.getFirst().value()).isEqualTo("{not json");
        assertThat(dead).allSatisfy(r -> {
            assertThat(header(r, "kafka_dlt-original-topic")).isEqualTo(userEventsTopic);
            assertThat(String.valueOf(header(r, "kafka_dlt-exception-fqcn"))
                    + header(r, "kafka_dlt-exception-cause-fqcn"))
                    .contains("NonRetryableEventException");
            assertThat(String.valueOf(header(r, "kafka_dlt-exception-message")))
                    .as("the DLT headers never quote the payload")
                    .doesNotContain("not json");
        });
    }

    private void expectCannotOrder(long userId, String reason) {
        client.post().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(orderRequest(userId))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.reason").isEqualTo(reason);
    }
}
