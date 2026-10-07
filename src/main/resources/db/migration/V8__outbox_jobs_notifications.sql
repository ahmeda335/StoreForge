-- Phase 9: reliable asynchronous work (ADR-005, ADR-016).
--
-- PLATFORM INFRASTRUCTURE (no RLS): outbox_events, jobs, email_messages. Workers must find work
-- across all tenants; only the jobs and notifications modules touch these tables, and every effect
-- on TENANT DATA still runs inside a tenant transaction (RLS) started from the row's store_id.
-- Event payloads are "thin" (ids, amounts), consumers re-read tenant data under RLS.

-- Events written in the SAME transaction as the business change (transactional outbox).
CREATE TABLE outbox_events (
    id              uuid        PRIMARY KEY,
    store_id        uuid,
    aggregate_type  text        NOT NULL,
    aggregate_id    text        NOT NULL,
    event_type      text        NOT NULL,
    event_version   integer     NOT NULL,
    payload         jsonb       NOT NULL,
    -- W3C traceparent of the producing request: the consumer's span continues the same trace.
    trace_parent    text,
    occurred_at     timestamptz NOT NULL,
    published_at    timestamptz
);
-- The relay's access path: only unpublished events (a tiny partial index).
CREATE INDEX ix_outbox_events_unpublished ON outbox_events (occurred_at) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_events_published ON outbox_events (published_at) WHERE published_at IS NOT NULL;

-- PostgreSQL job queue: claimed with FOR UPDATE SKIP LOCKED, leased, retried with backoff.
CREATE TABLE jobs (
    id            uuid        PRIMARY KEY,
    store_id      uuid,
    type          text        NOT NULL,
    -- Deduplicates fan-out: one job per (type, key), e.g. (event:order-emails, <event id>).
    dedup_key     text        NOT NULL,
    event_id      uuid,
    payload       jsonb       NOT NULL,
    status        text        NOT NULL,
    attempts      integer     NOT NULL DEFAULT 0,
    max_attempts  integer     NOT NULL,
    run_at        timestamptz NOT NULL,
    locked_by     text,
    locked_until  timestamptz,
    last_error    text,
    trace_parent  text,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    completed_at  timestamptz,
    CONSTRAINT uq_jobs_type_dedup UNIQUE (type, dedup_key),
    CONSTRAINT ck_jobs_status CHECK (status IN ('READY', 'RUNNING', 'DONE', 'DEAD')),
    CONSTRAINT ck_jobs_attempts CHECK (attempts >= 0 AND max_attempts > 0)
);
-- Workers poll these two partial indexes: due READY jobs, and RUNNING jobs whose lease expired.
CREATE INDEX ix_jobs_ready ON jobs (run_at) WHERE status = 'READY';
CREATE INDEX ix_jobs_running_lease ON jobs (locked_until) WHERE status = 'RUNNING';
CREATE INDEX ix_jobs_done ON jobs (completed_at) WHERE status = 'DONE';
CREATE INDEX ix_jobs_event ON jobs (event_id) WHERE event_id IS NOT NULL;

-- Outgoing emails: the row IS the outbox of the email system. Written in the business transaction;
-- a job sends it. At most one row per dedup key (e.g. one confirmation per order).
CREATE TABLE email_messages (
    id           uuid        PRIMARY KEY,
    store_id     uuid,
    dedup_key    text        NOT NULL,
    recipient    citext      NOT NULL,
    subject      text        NOT NULL,
    -- NULL after sending when the body contained a secret (verification / reset links).
    body         text,
    sensitive    boolean     NOT NULL,
    status       text        NOT NULL,
    sent_at      timestamptz,
    created_at   timestamptz NOT NULL,
    CONSTRAINT uq_email_messages_dedup UNIQUE (dedup_key),
    CONSTRAINT ck_email_messages_status CHECK (status IN ('PENDING', 'SENT')),
    CONSTRAINT ck_email_messages_body CHECK (status = 'SENT' OR body IS NOT NULL)
);
CREATE INDEX ix_email_messages_sent ON email_messages (sent_at) WHERE status = 'SENT';

-- TENANT DATA: the inbox of idempotent consumers. A consumer records the message id in the SAME
-- transaction as its effect; a redelivered message hits the primary key and is skipped.
CREATE TABLE processed_messages (
    store_id      uuid        NOT NULL,
    consumer      text        NOT NULL,
    message_id    uuid        NOT NULL,
    processed_at  timestamptz NOT NULL,
    CONSTRAINT pk_processed_messages PRIMARY KEY (store_id, consumer, message_id),
    CONSTRAINT fk_processed_messages_store FOREIGN KEY (store_id) REFERENCES stores (id) ON DELETE CASCADE
);
ALTER TABLE processed_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE processed_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON processed_messages
    USING (store_id = current_store_id()) WITH CHECK (store_id = current_store_id());

-- Purchase statistics maintained by an event consumer (demonstrates the inbox pattern).
ALTER TABLE customers ADD COLUMN orders_count integer NOT NULL DEFAULT 0;
ALTER TABLE customers ADD COLUMN total_spent_minor bigint NOT NULL DEFAULT 0;

-- Housekeeping of idempotency keys (tenant data, RLS) across all stores. A definer function that
-- only DELETES rows older than the cutoff and returns a count: it exposes no data (ADR-011, ADR-016).
GRANT SELECT, DELETE ON idempotency_keys TO storeforge_tenant_scanner;
CREATE FUNCTION purge_idempotency_keys(cutoff timestamptz) RETURNS integer
    LANGUAGE sql SECURITY DEFINER
    SET search_path = storeforge, pg_temp
AS $$
    WITH deleted AS (DELETE FROM idempotency_keys WHERE created_at < cutoff RETURNING 1)
    SELECT count(*)::integer FROM deleted
$$;
ALTER FUNCTION purge_idempotency_keys(timestamptz) OWNER TO storeforge_tenant_scanner;
REVOKE ALL ON FUNCTION purge_idempotency_keys(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION purge_idempotency_keys(timestamptz) TO ${app_role};
