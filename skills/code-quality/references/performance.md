# Performance Checks

What to look for in repositories, queries, and loops. How to fix it: `spring-boot` → `references/data.md`.

- **A query per row:** a repository call, or a lazy collection being read, inside a loop over entities (N+1).
- **Everything at once:** `findAll()` or an unbounded query on a table that grows.
- **Unsafe search patterns:** `LIKE` built from user input without escaping `%`, `_`, and `\`.
- **Missing index:** filtering or sorting on a column that no migration indexes (an `@Index` annotation alone does nothing when Flyway owns the schema).
- **Paging a collection fetch:** a `JOIN FETCH` or entity graph on a collection combined with paging, which makes Hibernate page in memory.
