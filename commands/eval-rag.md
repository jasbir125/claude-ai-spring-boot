---
description: Evaluate the retrieval and answer quality of a running RAG service against a golden question set (recall@k, no-answer accuracy, leak rate)
argument-hint: <base-url> [golden-file, default src/test/resources/rag/golden-questions.yml]
allowed-tools: Read, Bash, Glob, Grep
---

Evaluate the RAG service at `$ARGUMENTS` using `rag-patterns` → `references/testing-evaluation.md` ("Golden Set").

1. **Check the inputs.** A base URL is required; ask for it and stop if missing. Read the golden file (default `src/test/resources/rag/golden-questions.yml`); if it does not exist or has fewer than 10 questions, say so and stop with advice on building it. Ask the user for a **test token** for each group in the file (never create, guess, or log tokens). Confirm the instance is a staging or test environment, because every answerable question costs a model call; state the number of calls and stop for confirmation if it is above 100.
2. **Run each question** against `POST <base-url>/api/v1/questions` with the matching token, and record: HTTP status, `grounded`, the cited `sourceId` list, the prompt version, and the latency. Do not print or store the answer text beyond what the report needs; do not put tokens in command lines that are echoed or saved. Use the scratchpad for temporary files.
3. **Compute** recall@k (the expected sources are among the citations), MRR, no-answer accuracy (questions with no expected sources must be `grounded = false`), and the leak rate (a forbidden source appears in any citation; this must be 0). List every failing question with what was expected and what was returned.
4. **Judge answer quality only if asked.** Spot-check a small sample by reading the answer against the cited source; say it is a sample and a judgement, not a score.
5. **Report** the metrics in a table, the failing questions, the latency range, the number of model calls made, and the likely cause per failure with the knob to try from `rag-patterns` → `references/retrieval.md` ("Diagnosing Bad Answers"). Change one thing at a time and re-run to compare. A leak is Critical.

This command only reads the service from the outside. It never changes code, configuration, documents, or the index, and it does not commit.
