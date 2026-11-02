---
name: messaging-patterns
description: Event-driven messaging for Spring Boot 4.x - Kafka (and RabbitMQ) producers and consumers, transactional outbox, idempotent consumers (inbox), retries and dead-letter topics, ordering, event contracts and schema evolution, and Testcontainers tests. Use when the user adds events, Kafka, RabbitMQ, asynchronous integration, event sourcing, outbox, DLQ, or asks how services should communicate asynchronously.
metadata:
  version: "1.0.0"
  domain: backend
  triggers: Kafka, RabbitMQ, event-driven, event, message, broker, topic, queue, consumer, producer, outbox, inbox, idempotent consumer, DLQ, dead letter, retry topic, schema registry, Avro, CloudEvents, at-least-once
  role: specialist
  scope: implementation
  output-format: code
---

# Messaging Patterns Skill

Rules and patterns for asynchronous, event-driven communication between services. The broker is **Kafka** by default; use RabbitMQ only when the project already uses it or needs routing/priority queues (see "RabbitMQ" below).

## Non-Negotiable Rules

1. **Delivery is at-least-once.** Every consumer is idempotent. Never assume exactly-once across a database and a broker.
2. **No dual writes.** A service never writes to its database and publishes to the broker in the same code path without the **transactional outbox** (`references/outbox.md`). `kafkaTemplate.send()` inside `@Transactional` is a defect.
3. **Every event has a contract**: envelope with `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `aggregateId`, `correlationId` (`references/event-contracts.md`). Contracts evolve backward-compatibly.
4. **Key = aggregate ID.** Ordering is only guaranteed per partition; key by the aggregate whose events must stay ordered.
5. **Every consumer has bounded retries and its own dead-letter topic** `<topic>.<consumer-group>-dlt`, owned by the consumer (never a DLT shared between consumer groups). No infinite retry loops, no silently dropped messages. Non-retryable errors (validation, deserialization) go straight to the DLT.
6. **Consumers never call remote services inside a DB transaction** and keep handlers short; long work is split into further events.
7. **Events are facts in past tense** (`OrderPlaced`, `PaymentFailed`); commands are imperative (`ReservePayment`) and have exactly one owner.
8. **Each topic has one owning (producing) service.** Other services consume; they never publish to a topic they do not own.
9. **Never put secrets or unnecessary personal data in events.** Events are retained and replicated; reference IDs instead of copying PII.
10. **Test with a real broker** (Testcontainers `KafkaContainer` with `@ServiceConnection`), including duplicate delivery and DLT routing.

## Decision Guide

| Need | Use |
|------|-----|
| Notify other services that state changed | Domain event via outbox → Kafka topic owned by this service |
| Multi-service business transaction | Saga (see `architecture` skill, `references/saga.md`) |
| Reliable publish after DB commit | Transactional outbox (polling publisher or Debezium CDC) |
| Modular monolith, events between modules | Spring Modulith `ApplicationEventPublisher` + event publication registry; externalize to Kafka with `@Externalized` when other services need them |
| Request/response with immediate answer | Synchronous HTTP with timeouts (`conventions`, "Outbound HTTP"), not messaging |
| Transient failure in consumer | Blocking retry with backoff (`DefaultErrorHandler`) or non-blocking `@RetryableTopic` when order does not matter |
| Poison message | Per-consumer-group dead-letter topic `<topic>.<consumer-group>-dlt`, alert, replay tool that redelivers to that group only |
| Read model in another service | Consume events and build a local projection (CQRS). Plan its **rebuild path** first: compacted topic of full-state events, or a backfill from the owner (`architecture`, "Bounded Contexts") |

## What to Load

| Task | Reference |
|------|-----------|
| Kafka producer/consumer config, error handling, DLT, retry topics, ordering, consumer tests | `references/kafka.md` |
| Transactional outbox, polling publisher, Debezium, inbox / idempotent consumer tables | `references/outbox.md` |
| Event envelope, naming, versioning, schema registry, compatibility rules | `references/event-contracts.md` |
| Saga orchestration vs choreography, compensation | `architecture` skill → `references/saga.md` |

## Package Layout (per service)

```
src/main/java/<base-package>/
├── messaging/
│   ├── event/          # Event payload records (public contract, versioned)
│   ├── publisher/      # Outbox writer + outbox relay
│   ├── consumer/       # @KafkaListener classes (thin: map → service call)
│   └── config/         # Topics, error handler, serializers
└── ...
src/main/resources/db/migration/
    V<n>__create_outbox_and_inbox.sql
```

Listener classes are thin like controllers: they validate the envelope, deduplicate, and delegate to an application service.

## Checklist Before Merging Event-Driven Code

- [ ] Producer writes to the outbox in the same transaction as the state change
- [ ] Event payload is a record in `messaging/event`, versioned, documented (AsyncAPI or `docs/events.md`)
- [ ] Message key is the aggregate ID
- [ ] Consumer is idempotent (inbox table or naturally idempotent upsert) and tested with a duplicate message
- [ ] Error handler: bounded retries with backoff, non-retryable exceptions classified, per-consumer-group DLT `<topic>.<consumer-group>-dlt` configured and provisioned
- [ ] `ErrorHandlingDeserializer` wraps the value deserializer
- [ ] `correlationId` / trace context propagated (Micrometer observation enabled on template and listener)
- [ ] Consumer group ID is explicit and stable (`spring.application.name`-based)
- [ ] Topics declared (`NewTopic` bean for dev; IaC/GitOps for shared environments) with partitions, replication factor ≥ 3 in production, retention
- [ ] Integration test with Testcontainers Kafka: happy path, duplicate, poison message → DLT

## RabbitMQ

Use only when the project already uses it. The same rules apply: outbox for publishing, idempotent consumers, bounded retries (`spring.rabbitmq.listener.simple.retry.*` with `max-attempts`), a dead-letter exchange per queue (`x-dead-letter-exchange`), publisher confirms (`spring.rabbitmq.publisher-confirm-type: correlated`), and `@ServiceConnection` with `RabbitMQContainer` in tests. Ordering is per queue with a single active consumer only.
