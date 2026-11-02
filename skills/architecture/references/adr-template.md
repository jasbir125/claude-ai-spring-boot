# Architecture Decision Records

Write an ADR when a decision is hard to reverse or affects more than one team: choosing a broker, splitting a service, consistency model, database per service, auth model, public API/event versioning strategy, adopting a framework or platform component.

Location: `docs/adr/NNNN-kebab-case-title.md`, numbered sequentially, never edited after acceptance except for the status line. A changed decision is a new ADR that supersedes the old one.

## Template

```markdown
# NNNN. <Decision title in imperative form>

- Status: Proposed | Accepted | Superseded by NNNN | Deprecated
- Date: YYYY-MM-DD
- Deciders: <roles or team>

## Context

The problem, forces, and constraints (functional, quality attributes such as latency, availability, consistency, cost, team skills, compliance). Facts and numbers, not opinions.

## Options Considered

### Option A: <name>
- Pros:
- Cons:
- Risks / cost:

### Option B: <name>
- Pros:
- Cons:
- Risks / cost:

## Decision

The chosen option and the main reasons, tied back to the forces in Context.

## Consequences

- Positive:
- Negative / accepted trade-offs:
- Follow-up work (migrations, runbooks, monitoring, team training):

## Verification

How we will know the decision works (metrics, SLOs, fitness functions such as ArchUnit tests).
```
