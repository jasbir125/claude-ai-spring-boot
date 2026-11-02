package com.example.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.user.config.Correlation;
import com.example.user.dto.UserCreateRequest;
import com.example.user.dto.UserResponse;
import com.example.user.dto.UserUpdateRequest;
import com.example.user.messaging.publisher.OutboxEvent;
import com.example.user.messaging.publisher.OutboxEventRepository;
import com.example.user.messaging.publisher.OutboxWriter;
import com.example.user.model.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end against real PostgreSQL and Kafka: HTTP → service → outbox (same transaction) → relay → Kafka topic.
 * Only token validation is replaced (no identity provider in the test).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class UserApiIT {

    private static final String WRITER = "writer-token";
    private static final String READER = "reader-token";

    @Autowired
    private RestTestClient client;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Value("${app.messaging.user-events-topic}")
    private String topic;

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> switch (invocation.<String>getArgument(0)) {
            case WRITER -> jwt(WRITER, "users.read users.write");
            case READER -> jwt(READER, "users.read");
            default -> throw new BadJwtException("invalid token");
        });
    }

    @Test
    void register_returns201_andPublishesUserRegisteredThroughOutbox() {
        String email = uniqueEmail();
        String correlationId = "it-" + UUID.randomUUID();

        UserResponse created = client.post().uri("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .header(Correlation.HEADER, correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UserCreateRequest(email.toUpperCase(), " Ada Lovelace "))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueMatches(HttpHeaders.LOCATION, ".*/api/v1/users/\\d+")
                .expectHeader().valueEquals(Correlation.HEADER, correlationId)
                .expectBody(UserResponse.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.email()).isEqualTo(email);
        assertThat(created.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.version()).isZero();

        ConsumerRecord<String, String> record = awaitEvents(created.id(), 1).getFirst();
        JsonNode envelope = jsonMapper.readTree(record.value());
        assertThat(record.key()).isEqualTo(created.id().toString());
        assertThat(header(record, "eventType")).isEqualTo("UserRegistered");
        assertThat(envelope.get("eventType").asString()).isEqualTo("UserRegistered");
        assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(envelope.get("source").asString()).isEqualTo("user-service");
        assertThat(envelope.get("aggregateId").asString()).isEqualTo(created.id().toString());
        assertThat(envelope.get("correlationId").asString()).isEqualTo(correlationId);
        assertThat(envelope.get("payload").get("email").asString()).isEqualTo(email);
        assertThat(envelope.get("payload").get("version").asLong()).isZero();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(outbox.findByAggregateIdOrderByOccurredAt(created.id().toString()))
                        .singleElement()
                        .satisfies(row -> {
                            assertThat(row.getPublishedAt()).isNotNull();
                            assertThat(row.getId().toString()).isEqualTo(envelope.get("eventId").asString());
                        }));
    }

    @Test
    void updateThenDeactivate_publishesOrderedEvents_withIncreasingVersions() {
        UserResponse created = register(uniqueEmail(), "Grace");

        UserResponse updated = client.put().uri("/api/v1/users/{id}", created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UserUpdateRequest(created.email(), "Grace Hopper", created.version()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserResponse.class).returnResult().getResponseBody();
        assertThat(updated).isNotNull();
        assertThat(updated.version()).isEqualTo(1L);

        deactivate(created.id());
        deactivate(created.id());   // idempotent: no second event

        List<ConsumerRecord<String, String>> records = awaitEvents(created.id(), 3);
        assertThat(records).extracting(r -> header(r, "eventType"))
                .containsExactly("UserRegistered", "UserUpdated", "UserDeactivated");
        assertThat(records).extracting(r -> jsonMapper.readTree(r.value()).get("payload").get("version").asLong())
                .containsExactly(0L, 1L, 2L);
        assertThat(outbox.findByAggregateIdOrderByOccurredAt(created.id().toString())).hasSize(3);
    }

    @Test
    void duplicateEmail_differentCase_returns409_andWritesNoEvent() {
        String email = uniqueEmail();
        UserResponse first = register(email, "First");

        client.post().uri("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UserCreateRequest(email.toUpperCase(), "Second"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Email already in use")
                .jsonPath("$.errors[0].field").isEqualTo("email")
                .jsonPath("$.errors[0].code").isEqualTo("unique");

        assertThat(outbox.findAll()).filteredOn(e -> e.getPayload().contains(email)).hasSize(1)
                .extracting(OutboxEvent::getAggregateId).containsExactly(first.id().toString());
    }

    @Test
    void update_withStaleVersion_returns409() {
        UserResponse created = register(uniqueEmail(), "Linus");

        client.put().uri("/api/v1/users/{id}", created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UserUpdateRequest(created.email(), "Changed", created.version() + 5))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.title").isEqualTo("Version conflict");
    }

    @Test
    void list_filtersByStatus_andRejectsUnknownSortProperty() {
        UserResponse created = register(uniqueEmail(), "Listed");
        deactivate(created.id());

        String body = client.get().uri("/api/v1/users?status=DEACTIVATED&size=100&sort=id,desc")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + READER)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        JsonNode page = jsonMapper.readTree(body);
        assertThat(page.get("page").get("size").asInt()).isEqualTo(100);
        List<JsonNode> content = new ArrayList<>();
        page.get("content").forEach(content::add);
        assertThat(content).extracting(u -> u.get("status").asString()).containsOnly("DEACTIVATED");
        assertThat(content).extracting(u -> u.get("id").asLong()).contains(created.id());

        client.get().uri("/api/v1/users?sort=password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + READER)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo("Unknown sort property 'password'");
    }

    @Test
    void security_missingOrInvalidToken_returns401_readerCannotWrite_returns403() {
        client.get().uri("/api/v1/users").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer forged")
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + READER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UserCreateRequest(uniqueEmail(), "Nope"))
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
        assertThatThrownBy(() -> outboxWriter.append(topic, "User", "1", "UserRegistered", 1, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private UserResponse register(String email, String displayName) {
        UserResponse response = client.post().uri("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UserCreateRequest(email, displayName))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UserResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private void deactivate(Long id) {
        client.post().uri("/api/v1/users/{id}/deactivate", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + WRITER)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("DEACTIVATED");
    }

    /** Reads the topic from the beginning with a fresh group and returns the records keyed by the user ID. */
    private List<ConsumerRecord<String, String>> awaitEvents(Long userId, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(250)).forEach(r -> {
                    if (userId.toString().equals(r.key())) {
                        found.add(r);
                    }
                });
                assertThat(found).hasSize(expected);
            });
        }
        return found;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value());
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
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
