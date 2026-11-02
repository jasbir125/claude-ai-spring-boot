# Transactional Outbox and Inbox

## Why

`repository.save(order); kafkaTemplate.send(event);` loses events when the broker is down after the commit, and publishes phantom events when the commit fails after the send. The outbox stores the event in the **same database transaction** as the state change; a relay publishes it afterwards. Combined with idempotent consumers this gives effectively-once processing.

## Schema (Flyway, PostgreSQL)

```sql
-- V3__create_outbox_and_inbox.sql
CREATE TABLE outbox_event (
    id              UUID         NOT NULL,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(150) NOT NULL,
    schema_version  INT          NOT NULL,
    topic           VARCHAR(200) NOT NULL,
    payload         JSONB        NOT NULL,
    headers         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id)
);
CREATE INDEX ix_outbox_event_unpublished ON outbox_event (occurred_at) WHERE published_at IS NULL;

CREATE TABLE processed_event (
    event_id      UUID         NOT NULL,
    handler       VARCHAR(150) NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, handler)
);
```

Add a retention job (delete published rows older than N days, delete processed rows older than the broker retention plus a margin).

## Writing to the Outbox

```java
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orders;
    private final OutboxWriter outbox;


    @Transactional
    public OrderResponse place(PlaceOrderRequest request) {
        var order = orders.save(Order.place(request));
        outbox.append("Order", order.getId().toString(), new OrderPlaced(order.getId(), order.totalAmount()));
        return OrderResponse.from(order);
    }
}
```

`OutboxWriter.append` builds the envelope (`eventId` = new UUID, `correlationId` from MDC/trace context), serializes the payload with the application `JsonMapper`, and saves an `OutboxEvent` entity. It is annotated `@Transactional(propagation = MANDATORY)` so it fails fast when called outside a transaction.

Pitfalls:
- **Flush before building the event** when the event carries the aggregate `version` or generated values: `@Version` is incremented at flush, so use `saveAndFlush` (or `repository.flush()`) first, otherwise the event (and the API response) carries the old version.
- **Mapping the `jsonb` column** as a `String`: `@Column(columnDefinition = "jsonb") @ColumnTransformer(write = "?::jsonb") private String payload;` (passes `ddl-auto: validate`). `@JdbcTypeCode(SqlTypes.JSON)` on a `String` may serialize it as a JSON string literal. PostgreSQL `jsonb` normalizes key order and whitespace; consumers must not depend on byte-exact payloads (use `text` if signatures over the payload are needed).
- Name the scheduler property in plain milliseconds (`app.outbox.poll-interval-ms: 500`) or ISO-8601 (`PT0.5S`); do not assume `500ms` is accepted by `@Scheduled(fixedDelayString)`.

## Relay Option A: Polling Publisher (default, no extra infrastructure)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;


    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    public void relay() {
        tx.executeWithoutResult(status -> {
            // SELECT ... WHERE published_at IS NULL ORDER BY occurred_at LIMIT :batch FOR UPDATE SKIP LOCKED
            for (OutboxEvent event : outbox.lockNextBatch(100)) {
                try {
                    kafka.send(toRecord(event)).get(10, TimeUnit.SECONDS);  // wait for broker ack
                    event.markPublished(Instant.now());
                } catch (Exception ex) {
                    event.recordFailedAttempt();
                    log.warn("Outbox publish failed eventId={} attempts={}", event.getId(), event.getAttempts(), ex);
                    break;   // keep order: stop the batch at the first failure
                }
            }
        });
    }
}
```

Rules:
- `FOR UPDATE SKIP LOCKED` lets several replicas relay concurrently without double-sending the same row; otherwise use ShedLock so only one replica polls.
- With several relaying replicas, two events of the same aggregate can be locked by different replicas and sent out of order. Either run one active relay (ShedLock/leader election) when strict order matters, or put the aggregate `version` in every payload so consumers drop stale events (the default here).
- Mark a row published **only after the broker acknowledged** the send. A crash between ack and commit re-sends → consumers deduplicate.
- Order by `occurred_at`; key the record by `aggregate_id`.
- Expose metrics: pending outbox rows, oldest pending age, publish failures. Alert on age.
- **Limit attempts per row.** The relay stops at the first failure to keep order, so one row that can never be sent (too large, not serializable, not authorized) would block every later event forever. After `app.outbox.max-attempts` (e.g. 10), mark the row **parked** (`parked_at`, excluded from `lockNextBatch`), increment a metric, and alert. Parking breaks ordering for that aggregate, so the alert must lead to a manual fix and re-queue.
- **The relay's send timeout must be longer than the producer's `delivery.timeout.ms`.** Otherwise the relay gives up and re-sends while the producer is still retrying the first send, creating duplicates. Example: `delivery.timeout.ms: 30000` → relay waits at least 35 s (or lower `delivery.timeout.ms` to below the relay timeout).
- The transaction holds row locks only for one small batch with a bounded send timeout; keep the batch small.

## Relay Option B: Change Data Capture (Debezium)

For high throughput or many services: Debezium reads the outbox table from the WAL and publishes with the Outbox Event Router. The application only writes rows; no poller, no scheduler. Requires Kafka Connect and DB replication privileges. Choose it in an ADR (`architecture` skill).

## Option C: Spring Modulith Event Publication Registry

In a modular monolith, `ApplicationEventPublisher.publishEvent(...)` inside the transaction plus `spring-modulith-starter-jpa` persists the publication; `@ApplicationModuleListener` handles it after commit and incomplete publications are retried. Annotate the event with `@Externalized("orders.order.events::#{aggregateId()}")` and add `spring-modulith-events-kafka` to also publish to Kafka. Same guarantees as the outbox with less code.

## Inbox / Idempotent Consumer

```java
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

    @Modifying
    @Query(value = """
            INSERT INTO processed_event (event_id, handler) VALUES (:eventId, :handler)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId, @Param("handler") String handler);

    default boolean markProcessed(UUID eventId, String handler) {
        return insertIfAbsent(eventId, handler) == 1;
    }
}
```

The insert and the business change run in the same transaction: if the business change fails, the inbox row rolls back and the retry processes the event again.
