# Security Checks

What to look for in a change. The patterns to follow are in `spring-boot` → `references/security.md`; the house rules (no entities in responses, Problem Details, no exception text in errors, public `/actuator/health/**`) are in `conventions`.

- **Unchecked input:** path variables and query parameters used without validation or limits.
- **Injection:** SQL or JPQL built by string concatenation, native queries without parameters, user input in log patterns or shell commands.
- **Secrets in the code:** passwords, keys, or tokens in source, `application.yml`, tests, or Docker files (local demo values must be labelled and overridable).
- **Open doors:** a new endpoint without a matching security rule, a rule that is wider than needed, or access checked only in a front end.
- **Missing ownership check:** a caller can read or change another user's data because only a scope is checked.
- **Token checks:** issuer or audience not validated, or validation never tested with real signed tokens.
- **Leaks:** stack traces, SQL, internal IDs, or tokens in responses or logs.
