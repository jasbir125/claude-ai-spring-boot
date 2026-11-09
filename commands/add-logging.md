---
description: Add logging (correlation filter, log pattern, and log lines in every layer) to an existing Spring Boot service
argument-hint: [path-to-service, default current directory] [--setup-only]
allowed-tools: Read, Write, Edit, Bash, Glob, Grep
---

Add logging to the existing service at `$ARGUMENTS` (the current directory when no path was given), following the `spring-boot` skill's `references/logging.md` and the `spring-boot-engineer` agent's "Logging Is Automatic" rules. Do not create a new service.

1. **Locate the service.** Find its `pom.xml` and base package. If there is no Spring Boot project, say so and stop. Read `application.yml`, `CLAUDE.md` if present, and the existing log style (keep it).
2. **Set up the basics** (skip what already exists):
   - Lombok with `@Slf4j` available (dependency and `annotationProcessorPaths`)
   - `config/Correlation` and `config/CorrelationIdFilter`, copied from `examples/user-service` with the package changed
   - the `logging:` block in `application.yml` (`pattern.correlation`, `include-application-name: false`)
   - for services that consume Kafka messages, the `correlationId` MDC set and removed in a `finally` in each listener
3. **Add log lines to every layer**, using the "Where to Log" table:
   - services: INFO after each state change, WARN when a business rule refuses a request
   - exception handler: `log.error("Unexpected error", ex)` once for the fallback, WARN for notable domain errors
   - outbox relay, Kafka listeners and error handler, `@HttpExchange` clients, and scheduled jobs as the table says
   - controllers get none
   - replace `System.out`, `printStackTrace()`, and hand-made loggers with `@Slf4j`
   - with `--setup-only`, do step 2 only and stop after reporting
4. **Keep it safe.** Log identifiers only: no secrets, tokens, personal data, or payloads. Log each exception once. Do not change behaviour, signatures, or transactions, and do not touch tests except to add the filter test from `references/logging.md`.
5. **Verify.** Run `./mvnw -B clean verify` and fix failures at their cause. If Docker is unavailable and integration tests cannot run, say so.
6. **Report** the setup added, the log lines added per class (a short table), the commands run with results, and anything skipped. Suggest `/review` as the next step.

Do not commit or push.
