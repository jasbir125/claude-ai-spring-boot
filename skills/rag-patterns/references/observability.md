# Observability

Stage 12 of `pipeline.md` (the metrics half). Follows `spring-boot` → `references/logging.md` for logs and correlation, and `conventions` → Correlation.

## Metrics

Already in the reference code (Micrometer, exposed through `/actuator/prometheus`):

| Metric | Type | Meaning |
|--------|------|---------|
| `rag.answers{outcome=answered}` | counter | a grounded answer was returned |
| `rag.answers{outcome=no_context}` | counter | nothing above the threshold: no model call |
| `rag.answers{outcome=no_access}` | counter | caller had no permission groups |
| `rag.answers{outcome=model_error}` | counter | the model call failed |
| `rag.retrieval.results` | summary | chunks returned per question |

Add as the service grows (not in the reference code):

- ingestion: documents per status (`READY`, `FAILED`), ingest duration, chunks per document, embedding batches and failures;
- latency of retrieval and of generation, as timers, to see which stage is slow;
- model tokens in and out per call (Spring AI records them when observation is enabled; check the metric names of your version), to track cost;
- the dead-letter count of the ingestion topic, when a queue is used.

## Alerts That Matter

| Signal | Why |
|--------|-----|
| `no_context` share rising | retrieval broke, an ingestion run failed, or users ask about missing documents |
| `model_error` rate rising | provider problem, rate limiting, or an expired key |
| `FAILED` documents growing, or dead letters | documents that cannot be parsed or embedded |
| Token or cost per day above budget | abuse or a prompt that grew |
| p95 latency of generation | timeouts are near |

## Logs

From `logging.md`, applied to RAG:

| Place | Level | Line (identifiers only) |
|-------|-------|--------------------------|
| Ingestion success | INFO | `Document ingested sourceId={} chunks={}` |
| Ingestion skipped | INFO | `Document unchanged, skipped sourceId={}` |
| Ingestion failure | ERROR, once, with the exception | `Ingestion failed sourceId={}` |
| Question answered | INFO | `Question answered sources={}` |
| Question not answered | INFO | `Question not answered outcome={}` |
| Model failure | ERROR, once, with the exception | `Model call failed` |

**Never log** the question, the prompt, the chunk text, or the answer. A support case can be traced by the `correlationId` and the counts.

## Tracing

With Micrometer tracing and a collector in place, Spring AI emits observations for model calls and vector store operations, so one trace shows retrieval and generation time. Enable it only when the platform has a tracing backend (`CLAUDE.md` → Logging and observability). The exact observation names and the Boot 4 tracing artifacts were not verified here.

## Health

Expose `/actuator/health` with the database. Do **not** make readiness depend on the model provider: a provider outage should not take the whole service out of rotation, because the no-answer and ingestion-status paths still work. Surface provider failures through `model_error` metrics instead.
