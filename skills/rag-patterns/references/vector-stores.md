# Vector Store (pgvector), Schema, and Dependencies

Stage 5 of `pipeline.md`. pgvector is the default because the plugin's services already use PostgreSQL and Flyway: one database, one backup, one migration tool, and permission filters are plain metadata filters in the same query.

## Dependencies

```xml
<properties>
    <spring-ai.version>2.0.2</spring-ai.version>
</properties>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-vector-store-pgvector</artifactId></dependency>
    <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-tika-document-reader</artifactId></dependency>
    <!-- chat model and embedding model: one starter per provider you use -->
    <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-model-anthropic</artifactId></dependency>
    <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-model-openai</artifactId></dependency>
</dependencies>
```

Built and run with Spring Boot 4.1.2 and Spring AI 2.0.2. The Spring AI BOM is a separate import; the Spring Boot BOM does not manage it. A version other than 2.0.x was not tried. Check compatibility before upgrading either.

Test dependencies are the usual ones from `spring-boot` → `references/testing.md`, plus the pgvector container image (below).

## Schema (Flyway owns it)

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE vector_store (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT,
    metadata  JSONB,
    embedding VECTOR(1536)                       -- must equal the embedding model's output size
);

CREATE INDEX ix_vector_store_embedding ON vector_store USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ix_vector_store_source ON vector_store ((metadata ->> 'sourceId'));   -- for delete-by-document
```

`document_source` (bookkeeping) is in `ingestion.md`. The `CREATE EXTENSION` needs a database user that may create extensions, or an administrator who runs it once; plan for that in managed databases.

```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        initialize-schema: false     # Flyway owns the schema; Spring AI must not create tables
        dimensions: 1536
        index-type: hnsw
        distance-type: cosine-distance
        max-document-batch-size: 1000
```

These property names were read from the Spring AI 2.0.2 configuration metadata and used in the running service. Spring AI's default table name is `vector_store`; keep it, or set `spring.ai.vectorstore.pgvector.table-name`.

## Choices

| Choice | Default | Notes |
|--------|---------|-------|
| Distance | cosine | match the embedding model's recommendation |
| Index | HNSW | better recall and speed than IVFFlat for most corpora; it uses more memory and slows bulk loads |
| Dimension | from the embedding model | OpenAI `text-embedding-3-small` is 1536 by default; check your model's size and set the column to match |

## Embedding Model Changes

A different model usually means different vectors and often a different dimension. Vectors from two models are **not comparable**. Reindex everything (`ingestion.md`). For a new dimension, add a new table (or column and index), fill it by reindexing, switch the configuration, then drop the old one in a later migration. Never alter the column type in place on a table that serves traffic.

## When pgvector Is Not Enough

Consider a dedicated store when you have tens of millions of chunks, very high query rates, or need features like built-in hybrid ranking at scale. Until then the extra system is cost without benefit. Record the choice as an ADR (`architecture`) because it is hard to undo.

## Tests

Use a pgvector image, not plain PostgreSQL:

```java
@Bean
@ServiceConnection
PostgreSQLContainer<?> postgres() {
    return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
}
```

Flyway then runs the real migration, including the extension, in the test.
