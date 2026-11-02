---
description: Review the current branch (or a given ref) with the read-only code-reviewer agent
argument-hint: [base-ref, default main]
allowed-tools: Read, Bash, Glob, Grep, Agent
---

Review the changes on this branch against `$ARGUMENTS` (use `main` when no ref was given).

1. Run `git diff --stat <ref>...HEAD` and `git status --short`. If the ref does not resolve or there are no changes, say so and stop.
2. Launch the `code-reviewer` agent with the ref and a one-line summary of the change. Tell it which areas the diff touches (`db/migration/**`, entities, security configuration, Kafka/messaging) so it loads the matching references.
3. Present its report and end with a single recommendation: Approve | Approve with comments | Request changes.

Read-only: do not edit files or commit.
