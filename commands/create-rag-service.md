---
description: Scaffold a new production-ready RAG service (Spring Boot 4.x, Spring AI, pgvector): ingestion, permission-filtered retrieval, grounded answers with citations, tests, Compose, CI, docs
argument-hint: <service-name> [--chat anthropic|openai|ollama] [--embedding openai|ollama] [--ingest api|queue]
allowed-tools: Read, Write, Edit, Bash, Glob, Grep
---

Create a new RAG service from: `$ARGUMENTS`

1. **Parse the arguments.** Service name (kebab-case, becomes the artifact ID and directory). Defaults: `--chat anthropic`, `--embedding openai` (Anthropic has no embedding model), `--ingest api`. If the name is missing, ask for it and stop. `--ingest queue` adds Kafka-based ingestion (a pattern in `rag-patterns` → `references/ingestion.md`, not built in the verified reference service): load `messaging-patterns` and say in the report that it was not verified.
2. **Confirm the essentials** in one short question if they are not given: group ID and base package, document sources and rough volume, who may see which documents and which token claim carries the groups, and a few real questions with the document that answers each (the golden set). State every assumption.
3. **Follow the `rag-engineer` agent's rules and workflow** with the `rag-patterns`, `spring-boot`, and `conventions` skills. Build the stages of `rag-patterns` → `references/pipeline.md` in order.
4. **Generate:**
   - Maven project with wrapper; `pom.xml` using the Spring Boot BOM and the Spring AI BOM import from `rag-patterns` → `references/vector-stores.md`
   - Flyway migration for `vector_store` and `document_source`
   - Ingestion (load, parse, chunk, embed, store), with content-hash skip, stale-chunk removal, status table, delete, and reindex support
   - Retrieval with the permission filter, the versioned prompt, the no-answer path, citations, model timeout and token limits
   - Correlation filter, logging, metrics, Problem Details handler, resource-server security with `rag.query` and `rag.ingest` scopes
   - Tests per `rag-patterns` → `references/testing-evaluation.md`: pgvector Testcontainers, fake embedding model, mocked chat model, the permission and no-answer cases
   - `application.yml` with environment placeholders (keys empty by default), `compose.yaml` with a pgvector image, CI pipeline, `.gitignore`
   - `docs/golden-questions` or `src/test/resources/rag/golden-questions.yml` from the user's questions
   - `README.md` from the generated code using the `docs-writer` agent's templates, including the permission model and the reindex procedure
5. **Verify.** Run `./mvnw -B clean verify`; integration tests must run, not be skipped. If Docker is unavailable, say so explicitly.
6. **Report** the files created, the stages covered, decisions and assumptions, the commands run with results, and plainly what was **not** verified: real provider calls, retrieval quality on real documents, load, optional stages. Suggest `/eval-rag` against a staging instance and `/review`.

Do not commit. Do not call real model providers from tests or CI. Do not create deployment resources in any cluster.
