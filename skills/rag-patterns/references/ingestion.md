# Ingestion

Stages 1-5 of `pipeline.md`. Code from the reference service: `IngestionService`, `DocumentRegistry`.

## The Rules

1. **Idempotent.** Re-running ingestion over the same documents changes nothing and costs nothing.
2. **No gap.** A document is never left without chunks because an update failed half-way.
3. **Not in a transaction.** The embedding call is remote and slow; no database transaction stays open around it.
4. **Traceable.** Every document has a status row; every chunk knows which document, revision, and chunking version made it.

## Bookkeeping Table

`document_source` has one row per document: `source_id`, `content_hash` (SHA-256), `status` (`PROCESSING`, `READY`, `FAILED`), `chunk_count`, `embedding_model`, `chunking_version`, `failure_reason`. It answers "what is in the index?" without scanning vectors, and drives skip and reindex decisions.

## Skip, Replace, Remove

```java
String hash = sha256(content);
var existing = registry.find(sourceId);
if (existing.isPresent() && "READY".equals(existing.get().status())
        && hash.equals(existing.get().contentHash())
        && props.embeddingModel().equals(existing.get().embeddingModel())
        && props.chunkingVersion().equals(existing.get().chunkingVersion())) {
    return new IngestionResult(sourceId, UNCHANGED, 0);            // nothing to do, nothing to pay for
}
registry.markProcessing(sourceId, title, hash, props.embeddingModel(), props.chunkingVersion());
try {
    String revision = UUID.randomUUID().toString();
    List<Document> chunks = chunk(sourceId, title, aclGroup, filename, content, revision);
    vectorStore.add(chunks);                                       // embed in batches, then store
    vectorStore.delete(new FilterExpressionBuilder()               // stale chunks only after the new ones exist
            .and(new FilterExpressionBuilder().eq("sourceId", sourceId),
                 new FilterExpressionBuilder().ne("revision", revision)).build());
    registry.markReady(sourceId, chunks.size());
} catch (RuntimeException ex) {
    registry.markFailed(sourceId, "ingestion failed");             // the old chunks are still there
    log.error("Ingestion failed sourceId={}", sourceId, ex);
    throw new IngestionFailedException(sourceId, ex);
}
```

Removing a document: delete its chunks by `sourceId`, then its registry row.

Between the add and the delete both revisions exist for a moment, so a query may briefly see old and new text of the same document. That is accepted; a missing document would be worse.

## Parse and Chunk

```java
List<Document> parsed = new TikaDocumentReader(resource).get();            // PDF, Word, HTML, text, ...
List<Document> chunks = TokenTextSplitter.builder().withChunkSize(800).build().apply(parsed);
```

- Chunk size is in tokens and comes from `app.rag.chunk-size-tokens`. 500 to 1000 tokens is a common start; **tune it on the golden set**, not by guess.
- `TokenTextSplitter` has **no overlap setting**. If answers are cut at chunk borders, use a splitter with overlap or split by headings first.
- Tika extracts text but not layout: tables and multi-column pages may come out scrambled. Scanned PDFs have no text and need OCR before this step. Check a sample of real documents.
- An empty result (no extractable text) is a failure, not a success with zero chunks.
- Put on **every chunk**: `sourceId`, `title`, `aclGroup`, `chunkingVersion`, `revision`. The permission filter and stale-chunk removal depend on them. Add page or section if the parser provides it, so citations can point precisely.

## Embedding

`VectorStore.add` embeds in batches (`spring.ai.vectorstore.pgvector.max-document-batch-size`). Provider rate limits are the usual bottleneck, so:

- set `spring.ai.openai.max-retries` and a timeout, and ingest with bounded parallelism;
- never embed one chunk per call;
- store `embedding-model` with the document (done by the registry).

## Thousands of Documents

A few thousand documents is a small corpus (tens of thousands of chunks); pgvector handles it. What matters is that ingestion can be re-run, parallelised, and watched.

```
source ─▶ discover (id, hash, permissions) ─▶ queue, one message per document ─▶ worker: ingest(...) ─▶ pgvector
                                                                  └─ failed after retries ─▶ dead-letter topic
```

- **Queue and workers (pattern, not in the reference code).** Run ingestion as consumers of a topic, one message per document, with the rules in `messaging-patterns`: idempotent consumers, bounded retries, a dead-letter topic for documents that can never be parsed. One bad PDF must not stop the other 999. The reference service ingests synchronously through its API.
- **Discovery.** A job lists the source, compares hashes with `document_source`, and enqueues only new or changed documents and deletions.
- **Bounded parallelism.** Limit concurrent embedding calls to the provider's rate limit; add backoff on 429.
- **Visibility.** Count documents per status; alert on `FAILED` growth and on dead-letter messages (`observability.md`).
- **Order does not matter** between documents, so workers can scale out; for one document, ingestion must not run twice at the same time (key the message by `sourceId`).

## Reindex

Changing the embedding model, its dimension, or the chunking strategy invalidates the vectors.

1. Change the value in `application.yml` (`embedding-model`, or bump `chunking-version`). The skip check then fails for every document, so the next discovery run re-ingests all of them.
2. A new embedding **dimension** also needs a new column or table (`vector-stores.md`); vectors of different sizes cannot share one column.
3. Run it in the background and watch the status counts. Because new chunks are added before old ones are removed, search keeps working during the reindex.

## Deletions and Permission Changes

- A document removed at the source must be removed from the index (`remove`); otherwise it stays searchable. Discovery must detect deletions.
- A permission change at the source must update `aclGroup` on the chunks. The simplest safe way is to treat it as a change: re-ingest, or update the metadata of the document's chunks.
