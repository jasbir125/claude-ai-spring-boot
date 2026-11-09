---
description: Scaffold a new production-ready Spring Boot 4.x microservice (REST, persistence, optional Kafka events, tests, Compose, CI, docs)
argument-hint: <service-name> [--db postgres|mysql|none] [--events produce|consume|both|none]
allowed-tools: Read, Write, Edit, Bash, Glob, Grep
---

Create a new Spring Boot service from: `$ARGUMENTS`

1. **Parse the arguments.** Service name (kebab-case, becomes the artifact ID and directory). Defaults: `--db postgres`, `--events none`. If the service name is missing, ask for it and stop.
2. **Confirm the essentials** in one short question if they are not given and cannot be detected from a parent project: group ID / base package, the aggregates (main entities) the service owns, and for `--events` the topics it produces/consumes. State every assumption you make.
3. **Follow the `spring-boot-engineer` agent's rules and workflow** with the `conventions` and `spring-boot` skills. Load `messaging-patterns` when `--events` is not `none`.
4. **Generate:**
   - Maven project with wrapper, `pom.xml` using the Spring Boot BOM (no version overrides without a reason)
   - Layered packages: controller, service, repository, model, dto, config, exception (+ messaging)
   - Entities, Flyway migrations, repositories, DTO records, services, controllers, Problem Details exception handler
   - For events: envelope, event records, outbox writer + relay and outbox table (produce); idempotent listener + inbox table, error handler with DLT (consume)
   - `application.yml` with the production defaults from `spring-boot-engineer`, all environment-specific values externalized
   - Logging, always added without being asked, per `spring-boot` → `references/logging.md`: `Correlation` and `CorrelationIdFilter`, the `logging.pattern.correlation` setting, and `@Slf4j` log lines in services, the exception handler, the outbox relay, listeners, and clients
   - Tests: unit, slice, and Testcontainers integration tests (database, plus Kafka with duplicate and DLT cases when events are used)
   - `compose.yaml` with every local dependency, CI pipeline (match existing provider, default GitHub Actions), `.gitignore`
   - `README.md` (and `docs/events.md` with events) written from the generated code using the `docs-writer` agent's templates
5. **Verify.** Run `./mvnw -B clean verify` and fix failures at their root cause. Integration tests must run, not be skipped; if Docker is unavailable, say so explicitly.
6. **Report** the files created, design decisions and assumptions, the commands run with their results, and follow-up work (e.g. topic provisioning, identity provider setup, deployment). Suggest `/review` as the next step.

Do not commit. Do not create deployment resources in any cluster.
