# CLAUDE.md

Project-wide instructions for Claude Code. Apply these rules whenever working in this repository.
Keep this file concise and project-specific; put detailed, reusable technical guidance in `skills/`
and specialist responsibilities in `agents/`.

> **This repository is a Claude Code plugin.** Its agents, skills, and commands live at the repository root
> (`agents/`, `skills/`, `commands/`, `.claude-plugin/`), not under `.claude/`. This file guides work on the
> plugin itself and is not shipped to projects that install it; the rules the plugin enforces in those
> projects live in `skills/conventions/SKILL.md`.

## 0. Working on this plugin

- Each rule has one home: project rules in `skills/conventions`, detailed patterns in the topic skill, review
  signals in `skills/code-quality/references`, process in the agent. Link to the home instead of copying text.
- Skill frontmatter `description` decides when a skill loads: keep it specific and list concrete triggers.
- Agents declare `tools` (least privilege; reviewers are read-only) and `model`.
- Commands declare `description`, `argument-hint`, and `allowed-tools`, and never commit, push, or deploy
  without explicit confirmation.
- When adding or renaming an agent, skill, or command, update `README.md` (tables and directory structure).
- Bump `version` in `.claude-plugin/plugin.json` for each release (semantic versioning).
- Code samples must compile against the Spring Boot 4.x APIs they claim to use; when unsure of a Boot 4
  name or property, say so in the text instead of guessing.

## 1. Working approach

### Explore → Plan → Implement → Verify → Commit

1. **Explore** the repository before changing anything. Read `README.md`, `pom.xml`/Gradle files,
   application configuration, existing tests, and relevant agent/skill instructions.
2. **Plan** every non-trivial task (three or more steps, cross-cutting changes, or architectural decisions).
   State the intended design, files/modules affected, risks, and verification steps. Wait for approval
   when the task is ambiguous or the plan changes public APIs, data contracts, security, or infrastructure.
3. **Implement** incrementally. Follow existing conventions and make the smallest coherent change.
4. **Verify** by running relevant tests, static checks, and build/package commands. Never claim a check
   passed unless it was actually run and its result was inspected.
5. **Commit** only when explicitly requested. Before proposing a commit, inspect `git diff` and
   `git status`; never include secrets, generated files, or unrelated changes.

Skip formal planning for simple, obvious fixes. Do not overengineer.

## 2. Self-review and continuous improvement

- Challenge your own solution before presenting it: check correctness, simplicity, security, performance,
  maintainability, and test coverage.
- Find root causes; do not hide problems with temporary workarounds.
- If the user corrects a recurring project convention or identifies a mistake, ask whether it should
  become a durable project rule. If appropriate, record the lesson in `tasks/lessons.md` (create it only
  when useful).
- Do not silently change architecture, dependencies, public APIs, database schemas, event contracts,
  or deployment behavior.
- Explain important trade-offs and any checks that could not be run.

## 3. Skills and specialist agents

- Inspect `skills/`, `agents/`, and `commands/` (or `.claude/skills/` and `.claude/agents/` in a consuming
  project) before substantial work.
- Use the most relevant skill when its description matches the task. Follow its instructions and
  consult its reference files rather than duplicating them here.
- Delegate bounded, independent work to a specialist agent when it materially improves quality
  (for example architecture review, security review, tests, Docker, or Kubernetes).
- Give each agent a focused task and enough context; review and integrate its output yourself.
- Do not spawn agents for trivial changes or when coordination overhead exceeds the benefit.
- If the repository does not contain a referenced skill or agent, do not pretend it exists.

## 4. General engineering principles

- **Simplicity first:** prefer the simplest design that meets the requirements.
- **No laziness:** investigate the actual cause and implement a durable fix.
- **Consistency:** follow established patterns in the codebase unless there is a clear reason to improve them.
- **Explicitness:** prefer readable code and clear names over clever abstractions.
- **Small changes:** avoid unrelated refactoring and unnecessary dependencies.
- **Compatibility:** consider API, database, event-schema, and deployment compatibility.
- **Security by default:** validate inputs, enforce authorization, protect secrets, and use least privilege.
- **Operational readiness:** include useful logs, metrics, health checks, and graceful failure behavior.
- Do not add a framework, abstraction, library, or design pattern without a concrete need.

## 5. Java and Spring Boot conventions

- Use the Java and Spring Boot versions already selected by the project. Do not upgrade versions
  opportunistically; propose upgrades separately with compatibility rationale.
- Use the build tool already present (Maven or Gradle). Do not introduce a second build system.
- Follow the existing package structure and naming conventions.
- Prefer constructor injection; avoid field injection.
- Keep controllers thin. Put business rules in application/domain services, not controllers or repositories.
- Use DTOs for API input/output; do not expose persistence entities directly from public APIs.
- Validate request data at boundaries using Bean Validation where appropriate.
- Use centralized, consistent exception handling (for example, `@RestControllerAdvice`).
- Keep configuration externalized. Use typed configuration properties for related settings.
- Do not hard-code credentials, tokens, private keys, hostnames, or environment-specific values.
- Use Lombok first to remove boilerplate (rules in `skills/conventions`); keep records for DTOs.
- Use immutable value objects and records where they improve clarity and fit the project's Java version.
- Use `BigDecimal` for monetary values; define currency and rounding behavior explicitly.
- Avoid blocking calls in reactive flows. Do not mix MVC and WebFlux without a deliberate architectural reason.
- Keep transaction boundaries explicit and short. Do not hold database transactions open across remote calls.
- Use pagination for potentially large result sets.
- Prevent N+1 queries; inspect generated SQL and query plans when database performance matters.

## 6. Architecture and design

- Respect the architecture already present (modular monolith, layered architecture, hexagonal architecture,
  or microservices). Do not impose a new style without discussing the trade-offs.
- Keep domain logic independent of web, persistence, messaging, and infrastructure concerns where practical.
- Apply SOLID and dependency inversion pragmatically; avoid abstractions that add no value.
- Use domain-driven design concepts when they clarify bounded contexts, aggregates, invariants, and ownership.
- Define clear module/service responsibilities and avoid shared-database coupling between independently
  deployable services.
- For distributed workflows, explicitly consider consistency, retries, idempotency, timeouts, and
  compensating actions (for example, Saga where appropriate).
- For asynchronous messaging, define event ownership, schema evolution/compatibility, delivery semantics,
  duplicate handling, ordering assumptions, and dead-letter handling.
- Document significant architectural decisions (ADRs) when the decision is difficult to reverse.

## 7. API and integration practices

- Preserve existing API contracts unless a breaking change is explicitly approved.
- Use consistent HTTP methods, status codes, resource naming, validation, and error response formats.
- Document APIs with the project's OpenAPI conventions when applicable.
- Set timeouts for outbound HTTP calls. Configure bounded retries only for transient failures and
  only when the operation is safe to retry.
- Use circuit breakers/bulkheads where justified by dependency behavior and system requirements.
- Do not log authorization headers, access tokens, passwords, personal data, or sensitive payloads.
- For Kafka or other messaging systems, make consumers safe against duplicate delivery and define
  retry/DLQ behavior. Do not assume exactly-once processing across external systems.

## 8. Security requirements

- Follow the existing Spring Security configuration and supported version.
- Enforce authorization on the server; never rely on UI/client-side checks.
- Validate JWT signature, issuer, audience, expiry, and relevant claims according to the identity provider
  and application requirements.
- Do not implement custom cryptography or token formats when established standards/libraries suffice.
- Use OAuth2/OIDC flows appropriate to the client type. Do not store client secrets in source control.
- Apply least privilege to service identities, cloud IAM roles, database users, and Kubernetes service accounts.
- Validate and sanitize untrusted input; protect against injection, mass assignment, SSRF, and unsafe
  deserialization as relevant to the feature.
- Avoid exposing stack traces, internal identifiers, secrets, or sensitive data in API errors.
- Treat dependency vulnerabilities and insecure defaults as issues to report, not silently ignore.

## 9. Testing and verification

- Add or update tests for every behavior change.
- Cover positive cases, validation failures, authorization failures, edge cases, and relevant failure paths.
- Use JUnit 5 and the project's existing mocking/testing conventions.
- Prefer focused unit tests for business logic and integration tests for framework wiring, persistence,
  security, and messaging behavior.
- Use Testcontainers when a test needs a real database, broker, or other infrastructure and the project
  supports it. Do not replace meaningful integration tests with mocks alone.
- Keep tests deterministic: avoid dependence on external services, wall-clock timing, or shared mutable state.
- Do not weaken or delete a test merely to make the build pass; explain and fix the underlying issue.
- Run the narrowest relevant test first, then the module build, then the full build when feasible.
- Inspect failures and report skipped tests, unavailable infrastructure, and coverage gaps honestly.
- Do not claim a coverage percentage unless a coverage report was generated and checked.

Typical Maven commands (adapt module names and profiles to this repository):

```bash
./mvnw test
./mvnw verify
./mvnw -pl <module> -am verify
```

For Gradle projects, use the repository's wrapper and established tasks, for example `./gradlew test`
and `./gradlew check`.

## 10. Code quality and dependencies

- Follow existing formatting, linting, static-analysis, and compiler settings.
- Prefer supported, stable dependency versions compatible with the project's platform/BOM.
- Do not blindly use the newest version; verify compatibility and avoid unnecessary version overrides.
- Remove unused imports and dead code introduced by your changes.
- Keep methods focused and names descriptive.
- Avoid broad exception catches, swallowed exceptions, empty catch blocks, and unexplained magic values.
- Do not add generated code, build output, IDE metadata, or local environment files to commits unless required.

## 11. Logging and observability

- Use the project's logging facade and established structured logging conventions.
- Use appropriate log levels; avoid logging the same exception repeatedly at every layer.
- Include correlation/trace identifiers where the platform supports them.
- Never log secrets, credentials, tokens, or sensitive personal information.
- For new background processes or integrations, consider health, readiness, metrics, tracing, and actionable
  failure logs.
- Do not add an observability stack or agent unless it is part of the project's requirements.

## 12. Docker, CI/CD, and Kubernetes

- Reuse existing Dockerfiles, Compose files, pipelines, Helm charts, and Kubernetes manifests.
- Make containers reproducible, minimal, non-root where practical, and configurable through environment
  variables or mounted configuration.
- Do not bake secrets into images or manifests.
- Add or update health checks and resource requests/limits when relevant to the deployment model.
- Use readiness and liveness probes appropriately; do not use liveness to mask an unavailable dependency.
- Keep environment-specific configuration outside the application artifact.
- Update CI/CD only when the change requires it; preserve the repository's existing CI provider and conventions.
- Validate manifests/charts with available tooling when possible and state when validation was not run.

## 13. Documentation and delivery

- Update `README.md` when setup, configuration, API behavior, architecture, or operational procedures change.
- Document new configuration properties and provide safe example values (never real secrets).
- For non-trivial changes, summarize:
  1. What changed and why.
  2. Key design decisions and trade-offs.
  3. Files/modules affected.
  4. Tests and commands actually executed, including results.
  5. Known limitations, risks, and follow-up work.

## 14. Project-specific settings — confirm before changing

The following are examples, not universal mandates. Align them with this repository:

- Java version: use the version declared by the build.
- Spring Boot version: use the version declared by the build/BOM.
- Group ID and base package: preserve the existing project values.
- Artifact/module naming: follow the existing Maven/Gradle structure.
- CI provider: use the provider already configured (do not assume CircleCI).
- Container/deployment tooling: use what the project already uses (Docker Compose, Kubernetes,
  Helm, Skaffold, Terraform, etc.).
- Versioning: follow the repository's release/versioning policy; do not bump versions for every code edit.

## 15. Important boundaries

- Ask before destructive operations, data migrations that may lose data, force pushes, deployment to shared
  or production environments, credential rotation, or changes with material cost/security impact.
- Do not run commands that expose secrets or dump sensitive environment variables.
- Do not edit files outside the requested project scope unless necessary and explained.
- If requirements conflict with these instructions or existing code, identify the conflict and ask for
  clarification rather than guessing.
