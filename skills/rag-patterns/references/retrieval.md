# Retrieval

Stages 6-8 of `pipeline.md`. Retrieval decides the quality of the answer more than the model does: if the right chunk is not retrieved, no prompt can fix it.

## The Default Query

```java
List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
        .query(question)
        .topK(props.topK())                               // how many chunks at most
        .similarityThreshold(props.similarityThreshold()) // how similar at least
        .filterExpression(new FilterExpressionBuilder()
                .in("aclGroup", new ArrayList<Object>(callerGroups)).build())   // permission, inside the query
        .build());
```

- **Permission filter first.** It runs in the same SQL query as the similarity search, so a chunk the caller may not see is never read (`security.md`). With no groups, do not search.
- **top-k** (`app.rag.top-k`, default 5): more chunks raise recall and raise prompt cost and noise. 3 to 8 is a normal range.
- **Threshold** (`app.rag.similarity-threshold`, default 0.6): chunks below it are dropped. If nothing remains, answer "I don't know" without calling the model (`generation.md`). **The right value depends on the embedding model**; 0.6 is a starting point for the reference setup, not a rule. Tune it on the golden set: too high loses correct answers, too low lets unrelated text through.
- Map-based metadata keys (`sourceId`, `aclGroup`) must match what ingestion wrote.

## Filter Expressions

`FilterExpressionBuilder` builds the filter once for every vector store:

```java
var b = new FilterExpressionBuilder();
b.and(b.in("aclGroup", groups), b.eq("language", "en")).build();
```

Metadata filters also narrow by product, tenant, date, or document type. For multi-tenant services, `tenantId` is mandatory in every query, in the same way as the permission group.

The filter must be tested against the real database (Testcontainers), because the translation to SQL is the store's job.

## Query Handling (Stage 6)

- Validate: not blank, a maximum length (`@Size(max = 2000)`), no control characters you do not expect.
- Build the permission filter from the **validated token** (JWT claim), never from a request field.
- Optional **query rewriting** (stage 6, not in the reference code): for multi-turn chat, rewrite "and what about the second one?" into a standalone question with a small model call before searching. It costs one extra model call per question; add it only for conversational use.

## Optional: Hybrid Search

Pure vector search misses exact terms such as part numbers, error codes, and names. Hybrid search combines it with keyword search (PostgreSQL full-text search) and merges the two ranked lists, for example with reciprocal rank fusion. **Not in the reference code and not tested here.** Add it when the golden set contains exact-term questions that vector search misses.

## Optional: Reranking

A reranker re-scores the top 20 to 50 hits with a stronger model and keeps the best few. It improves precision at the cost of latency and money per question. **Not in the reference code and not tested here.** Add it when relevant chunks are retrieved but ranked below irrelevant ones (recall@20 is good, recall@5 is poor).

## Diagnosing Bad Answers

| Symptom | Likely cause | Try |
|---------|--------------|-----|
| The answer is "I don't know" but the document exists | threshold too high, chunk too small to carry the answer, wrong permission group | lower the threshold on the golden set; check chunking; check `aclGroup` |
| Right document, wrong passage | chunks too large or too small | change chunk size; split by headings |
| Exact codes or names missed | vector search weak on rare terms | hybrid search |
| Relevant chunk found but ranked low | embedding similarity not enough | reranking |
| Confident wrong answers | irrelevant chunks passed the threshold | raise the threshold; lower top-k; strengthen the prompt (`generation.md`) |
| Different answers each time | temperature too high | lower it (the reference uses 0.1) |

Measure before changing: `testing-evaluation.md`.
