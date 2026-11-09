---
name: rag-patterns
description: Building retrieval-augmented generation (RAG) services on Spring Boot 4.x with Spring AI - the 12-stage pipeline (load, parse, chunk, embed, store, query handling, retrieve, rerank, prompt, generate, cite, evaluate), pgvector schema with Flyway, ingestion of thousands of documents (content hash, idempotent re-ingest, stale-chunk removal, reindex), permission-filtered retrieval, grounded answers with citations, prompt-injection defence, model timeouts and cost limits, metrics, and tests with Testcontainers and golden questions. Use when the user adds RAG, a knowledge base, document Q&A, a chatbot over documents, embeddings, a vector store, pgvector, Spring AI, or asks how to ingest or search documents with an LLM.
---

# RAG Patterns Skill

How to build a retrieval-augmented generation service in Spring Boot 4.x. The house rules are in `conventions`; service structure is in `spring-boot`; logging is in `spring-boot` → `references/logging.md`. This skill adds what is specific to RAG.

The code in these files was built and run: Spring Boot 4.1.2, Java 25, Spring AI 2.0.2 (BOM `org.springframework.ai:spring-ai-bom`), PostgreSQL 17 with pgvector, 9 integration tests against a real pgvector container with an offline fake embedding model and a mocked chat model. **Not exercised:** calls to a real model provider, a Kafka ingestion queue, a reranker, hybrid search, streaming, and a circuit breaker. Those are described as patterns and marked as such.

## How to Work

1. **Read before writing.** Look at `pom.xml`, `application.yml`, existing packages, and whether the project already has a vector store or Spring AI.
2. **Agree the shape first:** the document sources and their size, who may see which documents (the permission model), the chat and embedding providers, and the expected questions. Ask for 10 to 20 real questions with the document that answers each; they become the golden set.
3. **Build the pipeline in order** (`references/pipeline.md`): migration → ingestion → retrieval with the permission filter → prompt and generation → API → tests.
4. **Prove it:** `./mvnw verify` with Testcontainers; then evaluate retrieval quality on the golden set (`references/testing-evaluation.md`).

## The Pipeline in One Picture

```
OFFLINE  source ─▶ load ─▶ parse ─▶ chunk ─▶ embed ─▶ store (pgvector + document_source)
ONLINE   question ─▶ validate + permission filter ─▶ retrieve ─▶ (rerank) ─▶ prompt ─▶ model ─▶ answer + citations
QUALITY  golden questions ─▶ recall@k, grounded rate ─▶ tune chunking, top-k, threshold
```

All 12 stages, with the file that covers each, are in `references/pipeline.md`.

## Package Layout

```
src/main/java/<package>/
├── ingestion/   IngestionService (load → store), DocumentRegistry, upload controller
├── query/       QuestionService (filter → retrieve → prompt → generate), controller, request/response records
├── config/      RagProperties (every tunable), security, correlation filter
└── exception/   Problem Details handler, ModelUnavailableException, IngestionFailedException
src/main/resources/
├── db/migration/   V1__rag_schema.sql  (vector_store, document_source)
└── prompts/        answer-v1.st        (versioned prompt)
```

## Reference Guide

| Topic | File | Read it when |
|-------|------|--------------|
| The 12 stages, in order, with the class for each | `references/pipeline.md` | Starting, or reviewing, any RAG service |
| Loading, parsing, chunking, embedding, thousands of documents, reindex | `references/ingestion.md` | Writing or changing ingestion |
| pgvector schema, indexes, dimensions, when to use another store | `references/vector-stores.md` | Touching tables, the vector store, or the embedding model |
| top-k, threshold, filters, hybrid search, reranking, query rewriting | `references/retrieval.md` | Answers are missing the right documents |
| Prompt, grounding, "I don't know", citations, streaming | `references/generation.md` | Writing the prompt or the answer format |
| Permissions, prompt injection, personal data | `references/security.md` | Always, before exposing the service |
| Timeouts, retries, limits, cost, caching | `references/resilience-cost.md` | Calling a model provider |
| Metrics, tracing, logs | `references/observability.md` | Adding or reviewing operations support |
| Unit, integration, and golden-question evaluation | `references/testing-evaluation.md` | Writing tests or measuring quality |

Related skills: `spring-boot`, `conventions`, `messaging-patterns` (for a queued ingestion pipeline), `architecture`.

## Always

- Apply the caller's permissions **inside the vector query**, never after it.
- Answer only from retrieved text; when nothing relevant is found, say so and **do not call the model**.
- Store with every chunk: source ID, title, permission group, chunking version, and revision.
- Keep every tunable (top-k, threshold, chunk size, model names, prompt version) in one `@ConfigurationProperties` record.
- Keep the prompt in a versioned file, with retrieved text marked as data.
- Make ingestion idempotent: skip unchanged documents, replace changed ones, remove deleted ones.
- Set timeouts and a bounded retry count on every model call, and a limit on prompt size.

## Never

- Search without a permission filter, or with an empty one
- Put retrieved text where the model could read it as instructions (the system prompt's rules, or unmarked in the user message)
- Hold a database transaction open during an embedding or model call
- Delete a document's old chunks before the new ones are stored
- Log questions, prompts, documents, or answers (identifiers only)
- Change the embedding model or chunking without a reindex
- Return the model's or the provider's error text to the caller
