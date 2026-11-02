---
name: conventions
description: The house rules for every Spring Boot service in this plugin - Lombok first, constructor injection, record DTOs, Problem Details errors, PagedModel lists, optimistic locking, Flyway-owned schemas, Testcontainers tests, and the distributed rules (transactional outbox, idempotent consumers, per-consumer dead-letter topics, timeouts, sagas). Loads for any Spring Boot, Java, Maven, JPA, Flyway, Kafka, or microservice work, and is the first thing reviews check against.
---

# Project Conventions

The rules every Spring Boot service in this plugin follows, for generated and reviewed code alike. **The project's own choices come first:** if its `CLAUDE.md`, build files, or decision records (ADRs) say otherwise, follow them and mention the difference.

## Mandatory Rules

**Code style**
- **Lombok first.** Let Lombok write boilerplate before writing it by hand:
  - Spring beans: `private final` fields with `@RequiredArgsConstructor`, which gives constructor injection with no hand-written constructor
  - Logging: `@Slf4j`
  - Plain classes: `@Getter`; `@Setter` only for fields that may change freely; `@Builder` when an object has many optional fields
  - JPA entities: `@Getter` and `@NoArgsConstructor(access = AccessLevel.PROTECTED)`. `@Data`, `@EqualsAndHashCode` and `@ToString` stay off entities, because they read lazy associations and misbehave with Hibernate proxies
  - DTOs and event payloads are **records**, which need no Lombok
  - Hand-written code only where it does real work, such as a constructor that also registers metrics
  - Build: `org.projectlombok:lombok` as an `optional` dependency (version from the Spring Boot BOM) **and** listed in `maven-compiler-plugin` → `annotationProcessorPaths`. JDK 23 and later ignore processors that are only on the class path
- **Constructor injection only**; never `@Autowired` on a field.
- **Records for DTOs**, named `<Resource>CreateRequest`, `<Resource>UpdateRequest`, `<Resource>Response`. Controllers never accept or return entities.
- **Jakarta** APIs (`jakarta.persistence`, `jakarta.validation`), not `javax`.

**HTTP and tests**
- `RestClient` (with `@HttpExchange` interfaces) for blocking HTTP calls, not `RestTemplate` or `WebClient`.
- Integration tests call the app through `RestTestClient` (`@AutoConfigureRestTestClient`).
- Real infrastructure in tests through Testcontainers with `@ServiceConnection`, not `@DynamicPropertySource`.
- Every behaviour gets a success test **and** the failure tests that matter.

**Project setup**
- Maven by default; keep Gradle where a project already uses it.
- Group ID and base package: keep existing ones. For a new project ask once (for example `com.<company>.<domain>`); never invent a placeholder.
- A new project's artifact ID is its folder name.
- Versions follow semantic versioning from `1.0.0`: PATCH for fixes, MINOR for compatible features, MAJOR for breaking API or event changes.
- `compose.yaml` starts every local dependency; a CI pipeline runs the full build (reuse the provider already in use, otherwise GitHub Actions).
- The README and the Mermaid diagrams in `docs/architecture.md` change together with any change to flows, data, events, or dependencies (`docs-writer` agent).

## Quality Bar

- New projects use the latest Spring Boot 4.x and Java 21 or newer; existing projects keep their versions until an upgrade is agreed separately.
- Services are `@Transactional(readOnly = true)` at class level; methods that change data add `@Transactional`.
- Request bodies on `POST`/`PUT` are `@Valid @RequestBody`.
- One `@RestControllerAdvice` handles errors. Every error, including the fallback `500`, is RFC 9457 Problem Details (`spring.mvc.problemdetails.enabled: true`), and the fallback never shows the exception message.
- Status codes say what happened: `201` with `Location` for create, `204` for delete without body, `409` for conflicts.
- Related settings are a `@ConfigurationProperties` record, not scattered `@Value` fields.
- Virtual threads on (`spring.threads.virtual.enabled: true`) and graceful shutdown (`server.shutdown: graceful`).
- Lists are `PagedModel`, never `Page`/`PageImpl`.
- Editable entities carry `@Version`; a lost update becomes `409`.
- Security permits `/actuator/health/**` (the whole subtree), so liveness and readiness probes work.

## Distributed and Event-Driven Rules

These apply as soon as a service sends or receives messages or calls another service. Details: `messaging-patterns` and `architecture`.

- **No dual writes.** A data change and the event announcing it are saved in the same database transaction (transactional outbox) and sent afterwards. `KafkaTemplate.send` inside a `@Transactional` business method is a defect.
- **Expect every message more than once.** Consumers are idempotent (inbox table or naturally idempotent upsert), and a test delivers the same message twice.
- **Retries have a limit, then the dead-letter topic.** Errors that can never succeed skip the retries. Each consumer group has its own dead-letter topic `<topic>.<consumer-group>-dlt`, owned by that consumer.
- **Events are contracts:** an envelope with `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `aggregateId`, `correlationId`; the aggregate ID is the message key; changes stay backward compatible and are documented in `docs/events.md`.
- **One owner per topic and per table.** Services never share a database.
- **Outbound HTTP:** an `@HttpExchange` client with connect and read timeouts, retries only for operations that are safe to repeat, a circuit breaker for remote dependencies, and a `503` Problem Details response when the dependency fails, without passing its error text through.
- **No transaction stays open during a remote call or a broker send.** The outbox relay's small, time-limited batch is the one documented exception.
- **Business processes across services are sagas** with a compensating step for each step and explicit timeouts (`architecture` → `references/saga.md`).
- **Correlation:** trace context and a `correlationId` travel over HTTP and Kafka and appear in logs; observation is on for HTTP clients and listeners.
- **Scheduled jobs in services with several replicas** coordinate through `FOR UPDATE SKIP LOCKED`, ShedLock, or a Kubernetes CronJob.
- **Decisions that are hard to undo** (broker, splitting a service, consistency model) are written down as an ADR in `docs/adr/`.

## Database Schema with Flyway

First decide whether Flyway is the right tool:

| The project… | Then |
|--------------|------|
| uses JPA or JDBC with a relational database Flyway supports | **Flyway owns the schema** |
| already uses Liquibase | keep Liquibase |
| has no relational database (none, or only MongoDB, Redis, Cassandra, Elasticsearch…) | no Flyway at all |

With Flyway:
- Scripts in `src/main/resources/db/migration`, one new `V<n>__<description>.sql` per change, never editing a script that has already run; the entity change and its script go into the same commit.
- Hibernate only checks the schema: `spring.jpa.hibernate.ddl-auto: validate`, plus `open-in-view: false`, and the database in the readiness group (`readinessState,db`).
- Primary keys are identity columns mapped with `GenerationType.IDENTITY` (`GENERATED BY DEFAULT AS IDENTITY` on PostgreSQL, Oracle, DB2, H2; `AUTO_INCREMENT` on MySQL/MariaDB; `IDENTITY` on SQL Server), not `SERIAL`.
- Constraints and indexes have explicit names (`pk_`, `uk_`, `fk_`, `ck_`, `ix_`); foreign-key columns are indexed; a unique constraint reported as `409` is matched by its name.
- Defaults and `CHECK` constraints mirror entity defaults and validation rules; a new `NOT NULL` column brings a default or a backfill.
- Integration tests run all scripts against the production database engine (Testcontainers) and confirm none is pending.

How-to and examples: `spring-boot` → `references/data.md` (Database Migrations).
