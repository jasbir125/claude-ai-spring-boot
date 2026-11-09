---
description: Add RAG (ingestion, permission-filtered retrieval, grounded answers) to an existing Spring Boot service
argument-hint: [path-to-service, default current directory] [--chat anthropic|openai|ollama] [--embedding openai|ollama]
allowed-tools: Read, Write, Edit, Bash, Glob, Grep
---

Add RAG to the existing service at `$ARGUMENTS` (the current directory when no path was given), following `rag-patterns` and the `rag-engineer` agent. Do not create a new service.

1. **Locate and read the service:** `pom.xml`, Spring Boot and Java versions, base package, `application.yml`, security configuration, database and Flyway, existing logging, tests. If it is not a Spring Boot project, say so and stop. If a vector store or Spring AI is already used, extend it instead of adding a second one.
2. **Check compatibility before changing anything:** the Spring Boot version against the Spring AI version in `rag-patterns` → `references/vector-stores.md` (verified: Boot 4.1.2 with Spring AI 2.0.2). Do not upgrade the project's Boot or Java version; if they do not fit, report it and stop.
3. **Confirm in one short question:** the document sources and volume, who may see which documents and which token claim carries the groups, the providers, and a few real questions for the golden set. State assumptions.
4. **Plan, then wait for approval** when the change touches the schema, security configuration, or public API: list the files, the migration, the new endpoints, and the new dependencies.
5. **Add only what is missing**, in pipeline order: dependencies and `spring.ai.*` configuration; the Flyway migration (a new version number after the existing ones; never edit an applied migration); ingestion; retrieval with the permission filter and its tests; prompt, generation, no-answer path, citations; scopes; handler entries; log lines and metrics; tests; Compose pgvector image if the project uses Compose.
6. **Keep the project's style and boundaries:** its package layout, error format, security model, and logging. Do not change existing endpoints, tables, or behaviour.
7. **Verify.** Run `./mvnw -B clean verify`; fix failures at their cause; report skipped integration tests.
8. **Report** files changed, the migration added, new configuration properties (with safe example values), commands run with results, and what was not verified. Suggest `/eval-rag` and `/review`.

Do not commit or push.
