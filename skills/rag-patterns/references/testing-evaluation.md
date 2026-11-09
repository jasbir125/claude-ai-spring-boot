# Testing and Evaluation

Stage 12 of `pipeline.md`. Two different jobs:

- **Tests** prove the pipeline works and is safe. They run in CI, offline, with no cost, and must be deterministic.
- **Evaluation** measures how good retrieval and answers are on your documents. It needs real models, costs money, and is run on demand.

## Test Setup (from the running reference service)

- **Real PostgreSQL with pgvector** through Testcontainers (`vector-stores.md`), so Flyway, the vector column, the index, and the filter all run for real.
- **A fake embedding model**, so no network and no cost. A hashed bag of words gives similar vectors to texts that share words, which is enough to test retrieval and filters:

```java
class FakeEmbeddingModel implements EmbeddingModel {
    static final int DIMENSIONS = 1536;                      // must match the column
    public EmbeddingResponse call(EmbeddingRequest r) { /* one vector per input text */ }
    public float[] embed(Document d) { return embed(d.getText()); }
    public float[] embed(String text) { /* hash each word into 1536 slots, then normalise */ }
}
```

Register it with `@Primary` in a `@TestConfiguration`. Set `spring.ai.anthropic.api-key=test` and `spring.ai.openai.api-key=test` so the context starts without real keys.

- **A mocked `ChatModel`** (`@MockitoBean`). `ChatClient` reads the model's default options, so stub them too: `when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build())`, otherwise the call fails with a `NullPointerException` inside `ChatClient`.
- **`@MockitoBean JwtDecoder`** and `jwt()` request post-processors for tokens with a `groups` claim.
- Call the API with `MockMvc` (`@AutoConfigureMockMvc`).
- Fake embeddings only compare shared words, so test questions need **real word overlap** with the document; a vague question scores below the threshold and returns "I don't know". In the reference tests the threshold is lowered to 0.3 (`app.rag.similarity-threshold=0.3`) for this reason. Do not use the fake model to judge real answer quality.

## Required Test Cases

| Case | Asserts |
|------|---------|
| Ingest then ask | grounded answer, citation points at the right source |
| Same content uploaded twice | second result `UNCHANGED`, chunk count unchanged |
| Changed content | old chunks gone, new ones searchable |
| Deleted document | its chunks are gone |
| Caller from another group | no answer, no citations, **model not called** (`verify(chatModel, never())`) |
| Caller with no groups | no answer, model not called |
| Unrelated question | no answer, model not called |
| Retrieved text in the prompt | the prompt contains `<documents>` and the data-not-instructions rule, with the chunk inside |
| Model failure | `503` Problem Details with a generic message, no provider text |
| Auth | no token `401`, wrong scope `403`, blank question `400` |
| Ingestion failure | status `FAILED`, old chunks kept (add when you add parsers that can fail) |

Run them with `./mvnw test -Dtest=<Class>` while developing and `./mvnw verify` in CI (name integration classes `*IT` for Failsafe, as the project's other services do; plain Surefire does not run `*IT` unless asked).

## Golden Set (Evaluation)

Build it with the user, from real questions.

```yaml
# src/test/resources/rag/golden-questions.yml
- question: How many days do customers have to return an item?
  expectedSources: [returns-policy]
  groups: [support]
- question: What are the salary bands?
  expectedSources: []            # support must NOT get this; expects a no-answer
  groups: [support]
```

Start with 20 to 50 questions covering the main documents, exact-term questions, questions with no answer, and questions the caller must not be able to answer. Keep it in the repository and add every bad answer found in production.

### Metrics

| Metric | Meaning | How to compute |
|--------|---------|----------------|
| Recall@k | the right document is among the top k hits | share of questions whose `expectedSources` appear in the citations |
| MRR | how high the right document ranks | mean of 1 / rank of the first correct source |
| No-answer accuracy | unanswerable and forbidden questions get "I don't know" | share with `grounded = false` |
| Leak rate | a forbidden document is cited | must be **0** |
| Grounded-answer quality | the answer is supported by the cited text | a person, or a model used as a judge with a written rubric; sample and spot-check |

Retrieval metrics (recall@k, MRR, no-answer, leak) can be computed without the chat model, from the citations. Answer quality needs the model and a judge.

### Running It

`/eval-rag` runs the golden set through the HTTP API of a running instance (a staging environment with the real models and real documents) and reports the metrics. The offline test suite cannot measure real retrieval quality, because its embedding model is fake. Cost: one chat call per answerable question; the cost is bounded by the size of the golden set.

### Tuning

Change one thing at a time and re-run: chunk size, chunking strategy, top-k, threshold, prompt version, embedding model, then optional stages (hybrid, rerank). Record the metrics next to the change. A change that lowers recall@k or raises the leak rate is not accepted.

## Review Signals

- No test with a caller who lacks permission, or the model is not verified as not called
- Tests that call real providers, or depend on network, or need a key to pass
- A mocked vector store where a Testcontainers one could be used (the filter and the schema stay untested)
- No golden set, or a golden set with no no-answer and no forbidden cases
- Thresholds or chunk sizes changed with no measured effect
