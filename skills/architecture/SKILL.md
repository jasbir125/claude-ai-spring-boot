---
name: architecture
description: Senior Java microservice and distributed systems architecture for Spring Boot - modular monolith vs microservices, bounded contexts and data ownership, layered vs hexagonal packages, sync vs async integration, consistency and sagas, idempotency, ArchUnit and Spring Modulith verification, and ADRs. Use when designing a new system or service, splitting a monolith, choosing between REST and events, designing a distributed transaction, or recording an architectural decision.
metadata:
  version: "1.0.0"
  domain: architecture
  triggers: architecture, HLD, LLD, microservices, modular monolith, bounded context, DDD, aggregate, hexagonal, ports and adapters, Spring Modulith, ArchUnit, saga, distributed transaction, eventual consistency, ADR, service boundary, API gateway
  role: architect
  scope: design
  output-format: document
---

# Architecture Skill

Design guidance for systems built from Spring Boot services. The `java-architect` agent runs the design process; this skill holds the knowledge.

## Default Stance

1. **Start with a modular monolith** (Spring Modulith) unless there is a concrete reason to distribute: independent scaling, independent deployment cadence owned by separate teams, different availability/compliance requirements, or a different technology need. "Microservices are modern" is not a reason.
2. **Boundaries follow the business** (bounded contexts), not technical layers. A service owns its data; no other service reads or writes its tables.
3. **Prefer asynchronous events between contexts** for state propagation; use synchronous calls only when the caller needs an answer now, and then with timeouts, retries for idempotent operations, and a circuit breaker.
4. **Accept eventual consistency explicitly**; where a business invariant spans services, model it as a saga with compensations (`references/saga.md`).
5. **Every hard-to-reverse decision gets an ADR** (`references/adr-template.md`) in `docs/adr/`.

## Monolith vs Microservices

| Signal | Modular monolith | Microservices |
|--------|------------------|---------------|
| Team count | 1–3 teams | Several teams owning separate contexts |
| Deployment | One release train is fine | Contexts must release independently |
| Scaling | Uniform load | One context has very different load/latency needs |
| Data | Strong consistency across contexts needed often | Contexts are loosely coupled, eventual consistency acceptable |
| Ops maturity | Limited platform/observability | CI/CD, tracing, central logging, on-call in place |

A modular monolith with enforced module boundaries and events between modules can be split later with little rework.

## Bounded Contexts and Aggregates

- One aggregate = one consistency boundary = one transaction. Reference other aggregates by ID, never by JPA relation across contexts.
- Keep aggregates small; invariants that need several aggregates are enforced by a process (saga/policy), not one big transaction.
- Context map: document upstream/downstream relations, shared kernel (avoid), anti-corruption layer for legacy/external systems.
- Identify the **system of record** for every piece of data. Copies elsewhere are read models fed by events.
- **Every read model (projection) needs a rebuild path.** A new consumer, a reset, or a bug fix must be able to rebuild it from scratch. Kafka deletes messages after its retention (7 days by default), so "replay the topic from the start" is only a rebuild path if the topic keeps each aggregate's **latest full state** forever: a compacted topic of state-carrying events (or snapshot events), keyed by aggregate ID. Otherwise provide a backfill (a bulk export or paged API from the system of record). Agree on it between producer and consumer and record it in an ADR.

## Package Structure

**Layered (default for simple CRUD services):** `controller / service / repository / model / dto / config / exception` — see `spring-boot-engineer`.

**Package-by-module + hexagonal (complex domain or modular monolith):**

```
<base-package>/
├── order/                      # module = bounded context
│   ├── OrderApi.java           # public facade other modules may call (module API)
│   ├── domain/                 # entities, value objects, domain services, domain events (no Spring web/messaging)
│   ├── application/            # use cases (@Service, @Transactional), ports (interfaces)
│   ├── adapter/in/web/         # controllers, DTOs
│   ├── adapter/in/messaging/   # @KafkaListener
│   ├── adapter/out/persistence/# JPA repositories implementing ports
│   └── adapter/out/client/     # HTTP clients to other services
├── payment/
└── shared/                     # only truly generic types (Money, IDs); keep tiny
```

Rules: dependencies point inward (`adapter → application → domain`); modules talk via the module API or events, never by reaching into another module's internals or repositories.

## Enforcing Boundaries

Spring Modulith (modular monolith):

```java
class ModularityTest {
    @Test
    void verifiesModuleStructure() {
        ApplicationModules.of(Application.class).verify();
    }
}
```

ArchUnit (any project):

```java
@AnalyzeClasses(packages = "<base-package>", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.kafka..", "jakarta.servlet..");

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses().that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule noCycles = slices().matching("<base-package>.(*)..").should().beFreeOfCycles();
}
```

## Integration Style Decision

| Question | Sync (REST/gRPC) | Async (events) |
|----------|------------------|----------------|
| Caller needs the result to continue? | ✅ | |
| Many consumers interested in a change? | | ✅ |
| Callee may be down without blocking the caller? | | ✅ |
| Strict latency budget end to end? | ✅ (with timeout budget) | |
| Long-running process (minutes/days)? | | ✅ (saga) |

Avoid synchronous call chains deeper than two hops; each hop multiplies failure probability and latency. Never build a "distributed monolith" where services must be deployed together.

## Distributed System Checklist

For every interaction between services, the design states:

- [ ] **Owner** of the data and of the API/topic
- [ ] **Consistency**: strong (same transaction) or eventual; staleness tolerated
- [ ] **Failure behavior**: timeout, retry policy (only idempotent operations), circuit breaker, fallback or fail fast
- [ ] **Idempotency**: idempotency key for commands/POSTs, dedup for consumers
- [ ] **Ordering** assumptions and the key that guarantees them
- [ ] **Compensation** for each saga step
- [ ] **Versioning** and backward compatibility of contracts (REST and events)
- [ ] **Observability**: correlation ID, trace propagation, metrics, alerts
- [ ] **Security**: service-to-service authentication (OAuth2 client credentials or mTLS), least privilege
- [ ] **Data**: retention, PII classification, backup/restore, migration strategy

## Cross-Cutting Building Blocks

| Concern | Default choice |
|---------|---------------|
| Edge | API gateway (Spring Cloud Gateway or platform ingress) for routing, auth token relay, rate limiting |
| Service-to-service auth | OAuth2 client credentials (JWT) or mesh mTLS |
| Config | Environment/ConfigMap + Secrets; Config Server only when dynamic central config is required |
| Discovery | Kubernetes Services/DNS; Eureka only outside Kubernetes |
| Messaging | Kafka (`messaging-patterns`) |
| Resilience | Connect/read timeouts on every client + Resilience4j circuit breaker |
| Caching | Local Caffeine or Redis, with TTL and eviction on change events |
| Observability | Actuator probes, Micrometer metrics, OTLP tracing, structured JSON logs |
| Scheduled jobs in replicas | ShedLock or Kubernetes CronJob |

## References

| Topic | File |
|-------|------|
| Saga orchestration vs choreography, compensation, state machine | `references/saga.md` |
| ADR template and when to write one | `references/adr-template.md` |
| Outbox, inbox, event contracts | `messaging-patterns` skill |
