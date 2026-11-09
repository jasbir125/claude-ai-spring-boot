---
name: code-quality
description: The review checklist for Java and Spring Boot changes - what to look for (correctness, API semantics, security, performance, distributed-system pitfalls, code smells) and how serious each finding is. Use when reviewing a diff, branch, or pull request, or when asked to check, clean up, or refactor code. The code-reviewer agent runs the review; this skill supplies the checks.
---

# Code Quality Checklist

What a reviewer looks for in a Java / Spring Boot change and how to rate it. The `code-reviewer` agent owns the process and the report; the rules themselves live in `conventions`, `spring-boot`, `messaging-patterns`, and `architecture`. The files here only say **how to spot** a problem and point to the rule, so each rule is written down once.

## When It Applies

- Reviewing a diff, branch, or pull request before it is merged
- "Check this code", "clean this up", "is this endpoint right?"
- Before releasing a change to an API or an event

## Which Files to Read

Read only what matches the change.

| The change touches | Read |
|--------------------|------|
| Any Java | `references/correctness.md` and `references/spring-conventions.md` |
| Controllers, DTOs, status codes, error responses | `references/api-contract.md` |
| Input handling, authentication, permissions, configuration, secrets | `references/security.md` |
| Repositories, queries, loops over entities | `references/performance.md` |
| Kafka listeners or templates, outbox/inbox, events, HTTP clients, retries, caches, scheduled jobs | `references/distributed.md` |
| Entities, SQL migrations, `spring.jpa.*` | `conventions` → Database Schema with Flyway, and `spring-boot` → `references/data.md` |
| Anything with real logic | `references/smells.md` |
| Any new or changed service, listener, client, relay, or exception handler | `spring-boot` → `references/logging.md` ("Review Signals") |

Where the underlying rules live:

| Topic | Source |
|-------|--------|
| House rules (Lombok first, records, Problem Details, Flyway, testing) | `conventions` |
| Fetching, N+1, transactions, locking, migrations | `spring-boot` → `references/data.md` |
| Test quality and Testcontainers | `spring-boot` → `references/testing.md` |
| Outbox, consumers, event contracts, dead-letter topics | `messaging-patterns` |
| Service boundaries, projections, sagas | `architecture` |

## How Serious Is It?

| Level | Meaning | Examples |
|-------|---------|----------|
| **Critical** | Must be fixed before merging | security hole, data loss or corruption, events for changes that never happened, crash on a normal path |
| **Important** | Should be fixed | wrong behaviour in an edge case, missing test for new behaviour, N+1 on a hot path, missing timeout |
| **Smell** | Worth a look | naming, length, duplication; always worded as "possible …" |
| **Good** | Worth repeating | a pattern others should copy |

## Out of Scope

- Anything a formatter or linter already enforces (whitespace, import order, brace style)
- Rewrites that only reflect taste and fix nothing concrete
- Choices the repository made on purpose: if `CLAUDE.md`, `conventions`, or an ADR says otherwise, the repository wins

## Quick Scan

| Area | Look for |
|------|----------|
| Nulls | `Optional.get()`, returned `null`, long getter chains |
| Exceptions | empty `catch`, catching `Exception`, a new exception without its cause |
| Transactions | multi-step writes outside `@Transactional`, calling a `@Transactional` method on `this` |
| API | state change on `GET`, wrong status code, contract change without a version bump |
| Data | loop that triggers a query per row, unbounded `findAll()`, unescaped `LIKE` |
| Distributed | Kafka send inside a business transaction, consumer without dedup, shared dead-letter topic, HTTP call without timeout |
