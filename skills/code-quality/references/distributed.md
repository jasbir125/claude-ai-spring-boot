# Distributed & Event-Driven Signals

Detection signals only; the rules live in `conventions` (Distributed and Event-Driven Rules), `messaging-patterns`, and `architecture`. Cite those, do not restate them.

## Messaging

- `kafkaTemplate.send(...)` / `rabbitTemplate.convertAndSend(...)` inside a `@Transactional` method or right after `repository.save` → dual write; needs outbox
- `kafkaTemplate.send(...)` result ignored where delivery matters (fire-and-forget without callback or ack)
- `@KafkaListener` that writes to the DB without dedup on `eventId` or an idempotent upsert
- Listener with business logic, remote calls, or `@Transactional` spanning an HTTP call
- No `CommonErrorHandler`/`@RetryableTopic`, or `FixedBackOff` with unlimited attempts; no DLT
- DLT shared between consumer groups (`<topic>-dlt`, or a fixed suffix used by several groups) instead of `<topic>.<consumer-group>-dlt`
- A projection (read model) built from another service's events with no rebuild path: the topic is time-limited (not compacted) or carries delta-only events, and there is no backfill; docs that say "replay from the start" anyway
- DLT runbook or code that re-publishes dead-lettered messages to the **source** topic instead of replaying inside the consumer
- Outbox relay with no attempt limit / parking (one unsendable row blocks all later events), or a relay send timeout shorter than the producer's `delivery.timeout.ms`
- JWT issuer/audience/expiry validation never exercised with real signed tokens (all tests mock `JwtDecoder` or use `jwt()`)
- Value deserializer not wrapped in `ErrorHandlingDeserializer`; `spring.json.trusted.packages: "*"`
- Message key `null` or random UUID for events that must stay ordered per aggregate
- Event record that removes/renames fields, changes types, or embeds an entity/secret/PII
- Publishing to a topic owned by another service; consumer group ID shared between different services
- `Thread.sleep` in messaging tests instead of Awaitility

## Outbox / Saga

- Outbox row marked published before the broker ack; relay without `SKIP LOCKED`/ShedLock running in every replica
- Unbounded outbox query (no `LIMIT`), missing partial index on unpublished rows, no retention
- Saga step without compensation, compensation not idempotent, no timeout handling, state transition without guard (duplicate/late message changes state)

## Synchronous Integration

- `RestClient`/`HttpClient`/`@HttpExchange` built without connect/read timeouts
- Retry on POST without idempotency key, retry on 4xx, retries without backoff or with > 3 attempts
- Circuit breaker health added to the readiness group
- Fallback that returns fabricated business data or swallows the error silently
- Downstream error body or exception message returned to the API caller
- `@Transactional` method calling a remote service
- Synchronous call chain of three or more services for a single request

## Caching / Scheduling

- `@Cacheable` without TTL/max size, on a method that returns an entity, or keyed without the user/tenant for per-user data
- `@Cacheable` method called from the same class (proxy bypassed)
- `@Scheduled` job that mutates shared state in a replicated service without ShedLock / `SKIP LOCKED`
