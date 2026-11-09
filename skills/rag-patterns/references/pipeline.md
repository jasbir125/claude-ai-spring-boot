# The RAG Pipeline

The single list of stages. Services are built in this order, and reviews check against it. Each stage is one class or one clearly named step with one job, so it can be tested and replaced alone.

| Phase | # | Stage | What happens | Class in the reference code | Details |
|-------|---|-------|--------------|-----------------------------|---------|
| Ingestion (offline) | 1 | Load | Read the document with its metadata: source ID, title, permission group, content hash | `DocumentController`, `IngestionService.ingest` | `ingestion.md` |
| | 2 | Parse | Extract text (OCR for scans), keep headings and tables where possible | `TikaDocumentReader` in `IngestionService.chunk` | `ingestion.md` |
| | 3 | Chunk | Split into pieces of a bounded token size; keep source, title, version on each | `TokenTextSplitter` | `ingestion.md` |
| | 4 | Embed | Turn chunks into vectors in batches; record the model name | `VectorStore.add` (calls the `EmbeddingModel`) | `ingestion.md` |
| | 5 | Store | Save vectors and metadata; skip unchanged, replace changed, delete removed | `VectorStore`, `DocumentRegistry` | `vector-stores.md` |
| Query (online) | 6 | Query handling | Validate the question; build the permission filter from the caller's token | `QuestionController`, `QuestionService.ask` | `security.md` |
| | 7 | Retrieve | Similarity search with top-k, threshold, and the permission filter in one query | `VectorStore.similaritySearch` | `retrieval.md` |
| | 8 | Rerank (optional) | Reorder the hits with a stronger model | not in the reference code | `retrieval.md` |
| | 9 | Build the prompt | Versioned template; retrieved text inside `<documents>` marked as data; bounded size | `prompts/answer-v1.st`, `QuestionService.context` | `generation.md` |
| | 10 | Generate | Call the chat model with timeout, bounded retries, and a token limit | `ChatClient` | `generation.md`, `resilience-cost.md` |
| | 11 | Respond | Answer with citations; "I don't know" when nothing relevant was found | `AskResponse` | `generation.md` |
| Quality | 12 | Evaluate | Golden questions: recall@k, grounded rate, no-access cases; metrics on every stage | tests, `/eval-rag`, Micrometer | `testing-evaluation.md`, `observability.md` |

## Order of Work for a New Service

1. Flyway migration: `vector_store` and `document_source` (`vector-stores.md`).
2. `RagProperties` and the `spring.ai.*` configuration.
3. Stages 1-5: `IngestionService`, `DocumentRegistry`, upload and delete endpoints.
4. Stages 6-7 with the permission filter, and its test, **before** any generation.
5. Stages 9-11: prompt, generation, citations, the no-answer path.
6. Security, Problem Details, logging, metrics.
7. Tests and the golden set (stage 12).

## What Reviewers Check Per Stage

| Stage | A finding if... |
|-------|-----------------|
| 1 | no content hash, so every upload is re-embedded |
| 3 | chunks lose their source ID or permission group |
| 4 | embeddings are one call per chunk (no batching), or the model name is not recorded |
| 5 | old chunks are deleted before new ones are stored; deleted documents leave chunks behind |
| 6-7 | the permission filter is missing, applied after the search, or skipped for an empty group list |
| 7 | no threshold, so the model always receives something |
| 9 | retrieved text is not marked as data; prompt size is unbounded |
| 10 | no timeout, no retry limit, or provider errors reach the caller |
| 11 | the model is called when nothing was retrieved; answers have no citations |
| 12 | no golden set, no test for a caller without access |
| any | prompts, questions, documents, or answers in logs |

## Optional Stages

Add these only when the golden set shows the need (`retrieval.md`): query rewriting, hybrid (keyword + vector) search, reranking. They add cost and latency; the default pipeline works without them.
