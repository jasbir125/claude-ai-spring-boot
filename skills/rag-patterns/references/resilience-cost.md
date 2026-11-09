# Resilience and Cost

Stage 10 of `pipeline.md`. Model providers are slow, rate-limited, and billed per token. Treat each call like any other remote dependency (`conventions` → Outbound HTTP) and add a cost limit.

## Configuration (from the running reference service)

```yaml
spring:
  ai:
    model:
      chat: anthropic          # with several starters on the class path, name one provider per capability
      embedding: openai
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}
      timeout: 30s
      max-retries: 2
      chat:
        options:
          model: ${RAG_CHAT_MODEL:claude-sonnet-5-5}
          max-tokens: 1024
          temperature: 0.1
    openai:
      api-key: ${OPENAI_API_KEY:}
      timeout: 30s
      max-retries: 2
      embedding:
        options:
          model: ${RAG_EMBEDDING_MODEL:text-embedding-3-small}
```

- Property names were read from the Spring AI 2.0.2 configuration metadata and the service started and ran with them.
- **Model names are examples, not verified against the providers.** Check the current model list of your provider before use, and keep the names in environment variables so they can change without a release.
- Anthropic has no embedding model; embeddings come from another provider (here OpenAI), or a local model such as Ollama. The chat and embedding providers are independent choices. Spring AI also has a starter for each provider; use the one you need.

## Timeouts and Retries

| Call | Timeout | Retries |
|------|---------|---------|
| Chat (user waits) | 30 s or less, shorter if the UI allows | 1 to 2 for transient errors (429, 5xx, timeouts) |
| Embedding at query time | 10 s | 1 to 2 |
| Embedding during ingestion | 30 s | more, with backoff; the job can wait |

Both calls are safe to repeat (no side effects). Never retry client errors (400, 401). Retries multiply cost and latency; keep them low.

## Circuit Breaker and Fallback (Pattern, Not in the Reference Code)

When the provider is down, every question waits for its timeout. Wrap the model call in a circuit breaker (`conventions` → Outbound HTTP) so that after repeated failures the service fails fast with `503`. A fallback to a second provider is possible but changes answer quality and data handling; decide it explicitly. The reference code catches failures and returns `503`; it has no breaker.

## Cost Controls

| Control | Where | Effect |
|---------|-------|--------|
| `max-tokens` | chat options | caps the answer length |
| `top-k` and `max-context-chars` | `RagProperties` | cap the prompt size |
| Threshold + no-answer path | `QuestionService` | no model call when nothing relevant was found |
| Unchanged-document skip | `IngestionService` | no embedding cost for documents that did not change |
| Batching | vector store | fewer embedding calls |
| Question length limit | `AskRequest` | bounds input tokens |
| Per-user rate limit | gateway or filter | bounds abuse |
| Budget alerts | provider console + metrics | notice a runaway early |

Record tokens per call (`observability.md`) so cost per question can be seen and checked, not guessed.

## Caching (Pattern, Not in the Reference Code)

- Cache embeddings of repeated questions (key: normalised text + embedding model).
- Cache answers only if the permission filter, prompt version, and document revision are part of the key, otherwise one user's answer can be served to another or can be stale. Many services should not cache answers at all.
- Some providers offer prompt caching for a repeated system prompt; it is a provider feature to evaluate, not assumed here.

## Ingestion Throughput

Provider rate limits, not the database, limit ingestion. Use bounded parallelism, backoff on 429, and the idempotent skip so a restart does not redo finished work (`ingestion.md`).
