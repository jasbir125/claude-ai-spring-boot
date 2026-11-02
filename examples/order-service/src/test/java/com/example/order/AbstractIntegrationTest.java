package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.order.dto.OrderCreateRequest;
import com.example.order.dto.OrderLineRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.repository.KnownUserRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared setup of the integration tests: one Spring context with real PostgreSQL and Kafka for all {@code *IT}
 * classes. Only token validation is replaced (no identity provider in the test). The tests play user-service by
 * publishing its events with a plain Kafka producer, exactly as documented in its contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractIntegrationTest {

    static final String WRITER = "writer-token";
    static final String READER = "reader-token";
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    RestTestClient client;

    @Autowired
    KafkaContainer kafka;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    KnownUserRepository knownUsers;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Value("${app.messaging.user-events-topic}")
    String userEventsTopic;

    @Value("${app.messaging.user-events-dlt-topic}")
    String userEventsDltTopic;

    @Value("${app.messaging.order-events-topic}")
    String orderEventsTopic;

    private KafkaProducer<String, String> userServiceProducer;

    @BeforeAll
    void startProducer() {
        userServiceProducer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"));
    }

    @AfterAll
    void closeProducer() {
        userServiceProducer.close();
    }

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> switch (invocation.<String>getArgument(0)) {
            case WRITER -> jwt(WRITER, "orders.read orders.write");
            case READER -> jwt(READER, "orders.read");
            default -> throw new BadJwtException("invalid token");
        });
    }

    // ---- user-service side --------------------------------------------------------------------------------------

    /** User IDs are owned by user-service; random IDs keep the tests independent of each other. */
    static long newUserId() {
        return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
    }

    /** Publishes a user-service envelope (contract v1) keyed by the user ID and returns its eventId. */
    UUID publishUserEvent(String eventType, long userId, long version, String correlationId) {
        UUID eventId = UUID.randomUUID();
        publishUserEvent(eventId, eventType, userId, version, correlationId);
        return eventId;
    }

    void publishUserEvent(UUID eventId, String eventType, long userId, long version, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        switch (eventType) {
            case "UserRegistered" -> {
                payload.put("email", "user-" + userId + "@example.com");
                payload.put("displayName", "Test User");
                payload.put("registeredAt", Instant.now().toString());
            }
            case "UserUpdated" -> {
                payload.put("email", "user-" + userId + "@example.com");
                payload.put("displayName", "Renamed User");
            }
            case "UserDeactivated" -> payload.put("deactivatedAt", Instant.now().toString());
            default -> { }
        }
        payload.put("version", version);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("source", "user-service");
        envelope.put("aggregateId", Long.toString(userId));
        envelope.put("correlationId", correlationId);
        envelope.put("causationId", null);
        envelope.put("payload", payload);
        publishRaw(Long.toString(userId), jsonMapper.writeValueAsString(envelope));
    }

    void publishRaw(String key, String value) {
        var record = new ProducerRecord<>(userEventsTopic, key, value);
        try {
            userServiceProducer.send(record).get();
        } catch (Exception ex) {
            throw new IllegalStateException("could not publish test event", ex);
        }
    }

    /** Registers the user through its event and waits until the projection knows it. */
    long registeredUser() {
        long userId = newUserId();
        publishUserEvent("UserRegistered", userId, 0, "it-register-" + userId);
        await().atMost(TIMEOUT).until(() -> knownUsers.findById(userId).isPresent());
        return userId;
    }

    // ---- order-service API --------------------------------------------------------------------------------------

    static OrderCreateRequest orderRequest(long userId) {
        return new OrderCreateRequest(userId, "EUR", List.of(
                new OrderLineRequest("SKU-1", 2, new BigDecimal("10.00")),
                new OrderLineRequest("SKU-2", 1, new BigDecimal("5.5"))));
    }

    OrderResponse placeOrder(long userId) {
        OrderResponse response = client.post().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(orderRequest(userId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    OrderResponse getOrder(long id) {
        OrderResponse response = client.get().uri("/api/v1/orders/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + READER)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    // ---- Kafka read side ----------------------------------------------------------------------------------------

    /** Reads {@code topic} from the beginning with a fresh group until {@code expected} matching records arrived. */
    List<ConsumerRecord<String, String>> awaitRecords(String topic, Predicate<ConsumerRecord<String, String>> filter,
                                                      int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            consumer.subscribe(List.of(topic));
            await().atMost(TIMEOUT).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(250)).forEach(r -> {
                    if (filter.test(r)) {
                        found.add(r);
                    }
                });
                assertThat(found).hasSizeGreaterThanOrEqualTo(expected);
            });
        }
        return found;
    }

    static String header(ConsumerRecord<String, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Jwt jwt(String token, String scope) {
        return Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject("it-client")
                .claim("scope", scope)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
