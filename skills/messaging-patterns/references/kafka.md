# Kafka with Spring Boot 4.x

## Dependencies

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-kafka</artifactId>
</dependency>

<!-- tests -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-kafka</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

Versions come from the Spring Boot BOM; do not override them. On Spring Boot 3.x projects use `spring-kafka` directly and `org.testcontainers:kafka`.

## Configuration

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all                       # wait for all in-sync replicas
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JacksonJsonSerializer
      properties:
        enable.idempotence: true      # no duplicates from producer retries
        max.in.flight.requests.per.connection: 5
        delivery.timeout.ms: 120000
        spring.json.add.type.headers: false   # contract is the schema, not the Java class name
    consumer:
      group-id: ${spring.application.name}
      auto-offset-reset: earliest
      enable-auto-commit: false       # container commits after the listener succeeds
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      properties:
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JacksonJsonDeserializer
        spring.json.trusted.packages: "<base-package>.messaging.event"
        isolation.level: read_committed
    listener:
      ack-mode: record
      observation-enabled: true       # tracing + correlation across the broker
    template:
      observation-enabled: true
```

`JacksonJsonSerializer`/`JacksonJsonDeserializer` are the Jackson 3 variants in Spring Kafka 4; on Spring Kafka 3.x use `JsonSerializer`/`JsonDeserializer`. For schemas shared across teams prefer Avro or Protobuf with a schema registry (see `event-contracts.md`).

Security (shared environments): `spring.kafka.security.protocol: SASL_SSL`, credentials from a Secret, never in `application.yml`.

## Topics

```java
@Configuration
public class KafkaTopicConfig {

    @Bean
    NewTopic orderEventsTopic(MessagingProperties props) {
        return TopicBuilder.name(props.orderEventsTopic())
                .partitions(props.partitions())
                .replicas(props.replicas())
                .build();
    }
}
```

`NewTopic` beans are for local/dev. In shared environments topics are created by infrastructure (Strimzi `KafkaTopic`, Terraform) and `spring.kafka.admin.auto-create: false`.

Naming:
- Domain events: `<bounded-context>.<aggregate>.events` (e.g. `orders.order.events`), owned by the producer.
- Retry and dead-letter topics are **per consumer group**: `<topic>.<consumer-group>-retry-N` and `<topic>.<consumer-group>-dlt` (e.g. `user.user.events.v1.order-service-dlt`). They are owned by the **consumer**, not by the producer of `<topic>`.

Why per consumer group and not a shared `<topic>-dlt`: several services consume the same topic. With one shared DLT, a replay tool cannot tell which group failed and would redeliver a message to groups that already processed it. One team's poison messages also show up in another team's alerts, and two services would publish to a topic neither of them owns. A per-group DLT keeps "one owner per topic", replays reach exactly the group that failed, and DLT alerts belong to one team.

## Error Handling: Blocking Retries + DLT (default)

Keeps per-partition ordering. Use it when events for the same aggregate must be processed in order.

```java
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template,
                                          @Value("${spring.kafka.consumer.group-id}") String groupId) {
        // per-consumer-group DLT, e.g. user.user.events.v1.order-service-dlt (owned by this service)
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + "." + groupId + "-dlt", record.partition()));
        var backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000);

        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                ValidationException.class,
                DeserializationException.class,
                NonRetryableEventException.class);
        return handler;
    }
}
```

Spring Boot wires a `CommonErrorHandler` bean into the auto-configured listener container factory. The DLT must have at least as many partitions as the source topic when the recoverer keeps the partition. When one service has listeners in several groups, build the name from the group of the failed record's listener (or define one error handler per container factory) so each group still gets its own DLT.

## Error Handling: Non-Blocking Retry Topics

Use when throughput matters more than ordering (a failing message must not block the partition).

```java
@Component
@RequiredArgsConstructor
public class PaymentEventsListener {

    private final PaymentService paymentService;


    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delay = 1000, multiplier = 2.0, maxDelay = 30000),
            exclude = {ValidationException.class, NonRetryableEventException.class},
            retryTopicSuffix = ".billing-service-retry",   // per consumer group: <topic>.<group>-retry-N
            dltTopicSuffix = ".billing-service-dlt")       // per consumer group: <topic>.<group>-dlt
    @KafkaListener(topics = "${app.messaging.payment-events-topic}", groupId = "billing-service")
    public void on(EventEnvelope<PaymentCompleted> event) {
        paymentService.markPaid(event);
    }

    // Suffixes are written as literals here; whether placeholders such as ${spring.application.name} are resolved
    // in these attributes depends on the Spring Kafka version, so check the reference before relying on it.
    @DltHandler
    public void onDeadLetter(EventEnvelope<PaymentCompleted> event,
                             @Header(KafkaHeaders.EXCEPTION_MESSAGE) String reason) {
        // log at ERROR with eventId/correlationId (no payload), increment a metric; alerting watches it
    }
}
```

## Idempotent Consumer

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventsListener {

    private final InventoryService inventoryService;


    @KafkaListener(topics = "${app.messaging.order-events-topic}")
    public void on(EventEnvelope<OrderPlaced> event) {
        MDC.put("correlationId", event.correlationId());
        try {
            inventoryService.reserve(event);   // dedup + business change in ONE transaction
        } finally {
            MDC.remove("correlationId");
        }
    }
}

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProcessedEventRepository processedEvents;
    private final StockRepository stock;


    @Transactional
    public void reserve(EventEnvelope<OrderPlaced> event) {
        if (!processedEvents.markProcessed(event.eventId(), "inventory.reserve")) {
            return;                            // duplicate delivery: already handled
        }
        // business change ...
    }
}
```

`markProcessed` inserts into the inbox table with a unique `(event_id, handler)` key (`outbox.md`, "Inbox"). An upsert keyed by a business ID is an acceptable alternative when the operation is naturally idempotent.

## Ordering

- Same key → same partition → ordered. Key every event by aggregate ID.
- Ordering breaks with: non-blocking retry topics, changing partition count of an existing topic, multiple producers for one aggregate.
- Consumers that need strict state transitions also check the aggregate `version` or a sequence number in the event and ignore stale events.

## Concurrency and Throughput

- `spring.kafka.listener.concurrency` ≤ number of partitions; extra threads stay idle.
- Batch listeners only for idempotent bulk operations; handle partial failure with `BatchListenerFailedException`.
- Keep `max.poll.interval.ms` above the worst-case handler time; long work → emit a command event instead.

## Integration Test

```java
@SpringBootTest
@Testcontainers
class OrderEventsIntegrationTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka-native:4.2.2");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired StockRepository stock;
    @Autowired ProcessedEventRepository processedEvents;

    @Test
    void orderPlaced_deliveredTwice_reservesStockOnce() {
        var event = EventEnvelopes.orderPlaced("order-1", "sku-1", 2);

        kafkaTemplate.send("orders.order.events", event.aggregateId(), event);
        kafkaTemplate.send("orders.order.events", event.aggregateId(), event);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(stock.findBySku("sku-1").orElseThrow().reserved()).isEqualTo(2));
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    void malformedEvent_goesToDeadLetterTopic() {
        // send invalid JSON with a raw producer, then consume from "orders.order.events.inventory-service-dlt" and assert it arrived
    }
}
```

Always test: happy path, duplicate delivery, poison message → DLT, and (for sagas) the compensation path. Use Awaitility, never `Thread.sleep`.

## Replaying from the DLT

- **Replay inside the consumer that owns the DLT.** Never re-publish a dead-lettered message to the source topic: every other consumer group would receive it again, and the consumer would become a second producer on a topic it does not own.
- Provide a replay listener on `<topic>.<consumer-group>-dlt` that is **off by default** (`autoStartup = "false"`, started by an operator through a feature flag or an admin endpoint). It calls the same application service as the normal listener, so the inbox and version checks make a replay safe.
- Fix the cause first; a replay of an unfixed poison message lands in the DLT again.
- When the data itself is wrong at the source, the producer team re-publishes a corrected event; the consumer does not invent one.
