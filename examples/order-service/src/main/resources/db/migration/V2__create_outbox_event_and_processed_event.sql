-- Transactional outbox: rows are written in the same transaction as the state change
-- and published to Kafka by OutboxRelay (polling publisher, FOR UPDATE SKIP LOCKED).
CREATE TABLE outbox_event (
    id              UUID         NOT NULL,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(150) NOT NULL,
    schema_version  INT          NOT NULL,
    topic           VARCHAR(200) NOT NULL,
    payload         JSONB        NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT ck_outbox_event_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_outbox_event_unpublished ON outbox_event (occurred_at) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_event_published_at ON outbox_event (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX ix_outbox_event_aggregate ON outbox_event (aggregate_type, aggregate_id);

-- Inbox: one row per consumed event and handler, inserted in the same transaction as the handler's changes.
-- A duplicate delivery hits the primary key and is skipped (idempotent consumer).
CREATE TABLE processed_event (
    event_id      UUID         NOT NULL,
    handler       VARCHAR(100) NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, handler)
);

CREATE INDEX ix_processed_event_processed_at ON processed_event (processed_at);
