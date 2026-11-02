# Event Contracts and Schema Evolution

Events are a public API. Treat them with the same discipline as REST contracts: versioned, documented, backward compatible.

## Envelope

```java
public record EventEnvelope<T>(
        UUID eventId,            // unique per event; consumers deduplicate on it
        String eventType,        // "OrderPlaced"
        int schemaVersion,       // 1, 2, ... (major version of the payload)
        Instant occurredAt,      // when the fact happened (UTC)
        String source,           // producing service, e.g. "order-service"
        String aggregateId,      // also the Kafka message key
        String correlationId,    // business flow / saga ID, propagated end to end
        String causationId,      // eventId of the message that caused this one
        T payload) {
}
```

Alternatively follow CloudEvents (`id`, `type`, `source`, `time`, `specversion`, `dataschema`) with Kafka headers binding when consumers outside the organisation need a standard. Pick one envelope per organisation and use it everywhere.

## Payload Rules

- Records in `messaging/event`, named as past-tense facts: `OrderPlaced`, `OrderCancelled`.
- Carry what consumers need to act without calling back (event-carried state transfer) but no secrets and minimal PII.
- When other services build projections from a topic, each event carries the aggregate's **full current state** needed by them (not only the changed field), and the topic is **compacted** (`cleanup.policy=compact`, key = aggregate ID). Then the latest event per key is enough to rebuild a projection at any time. A delta-only event ("email changed") on a time-limited topic cannot rebuild anything once older events are deleted.
- Monetary amounts as `BigDecimal` serialized as a string plus an ISO-4217 `currency` field.
- Timestamps as `Instant` (ISO-8601 UTC). IDs as strings/UUIDs, never database sequence values that leak internal state when an external ID exists.
- Enums: consumers must tolerate unknown values (map to `UNKNOWN`).

## Compatibility Rules

| Change | Compatible? | How |
|--------|-------------|-----|
| Add optional field | ✅ | Consumers ignore unknown fields (`FAIL_ON_UNKNOWN_PROPERTIES` off — the Spring Boot default) |
| Add required field | ❌ | Add as optional with default, make required in the next major |
| Remove / rename field | ❌ | Deprecate, keep emitting, remove in next major |
| Change field type or meaning | ❌ | New field or new major version |
| New event type on the topic | ✅ | Consumers ignore types they do not handle |

A breaking change gets a new `schemaVersion` major. The producer publishes both versions (or a new topic `...events.v2`) until all consumers migrated; record the migration plan in an ADR.

## Schema Registry (multi-team)

- Avro or Protobuf with Confluent/Apicurio schema registry; compatibility mode `BACKWARD_TRANSITIVE` (or `FULL_TRANSITIVE` when consumers upgrade before producers).
- Schemas live in the producing service repo (`src/main/avro` / `src/main/proto`), generated classes are not committed.
- CI checks compatibility against the registry before deploy.

JSON without a registry is acceptable inside one team; then add a contract test that serializes a sample of every event and compares it with a checked-in JSON example (`src/test/resources/contracts/events/*.json`).

## Documentation

Document every topic in `docs/events.md` or an AsyncAPI file (`docs/asyncapi.yaml`): topic, owner, key, event types, schema version, retention, consumers, example payload. Update it in the same change as the event.
