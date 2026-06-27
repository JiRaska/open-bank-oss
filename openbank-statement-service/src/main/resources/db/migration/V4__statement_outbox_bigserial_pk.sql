-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0049 D3: migrate statement_outbox.id from UUID to BIGSERIAL so that the entity can extend
-- PanacheOutboxEntity (which extends PanacheEntity with a generated Long id).
--
-- Hibernate Reactive + PanacheEntity allocate ids from a sequence named "<table>_seq"
-- (allocationSize 50). We recreate the table with BIGSERIAL and add the required sequence alias.
--
-- Rollback:
--   DROP TABLE statement_outbox;
--   DROP SEQUENCE IF EXISTS statement_outbox_seq;
--   CREATE TABLE statement_outbox (
--       id UUID PRIMARY KEY, event_id UUID NOT NULL UNIQUE, aggregate_id UUID NOT NULL,
--       event_type VARCHAR(255) NOT NULL, payload TEXT NOT NULL,
--       status VARCHAR(32) NOT NULL DEFAULT 'PENDING', attempt_count INTEGER NOT NULL DEFAULT 0,
--       sent_at TIMESTAMPTZ, last_error TEXT, created_at TIMESTAMPTZ NOT NULL,
--       updated_at TIMESTAMPTZ NOT NULL);
--   CREATE INDEX ix_statement_outbox_status ON statement_outbox (status, created_at);

-- Drop and recreate the outbox table with a BIGSERIAL pk (no live data in sandbox: outbox rows are
-- transient and any in-flight PENDING rows will be re-emitted on restart via the idempotency-key).
DROP TABLE IF EXISTS statement_outbox;

CREATE TABLE statement_outbox (
    id              BIGSERIAL       PRIMARY KEY,
    event_id        UUID            NOT NULL UNIQUE,
    aggregate_id    UUID            NOT NULL,
    event_type      VARCHAR(255)    NOT NULL,
    payload         TEXT            NOT NULL,
    status          VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER         NOT NULL DEFAULT 0,
    sent_at         TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL
);

CREATE INDEX ix_statement_outbox_status
    ON statement_outbox (status, created_at);

-- Hibernate Reactive + PanacheEntity allocate ids from a sequence named "<table>_seq"
-- (allocationSize 50); the BIGSERIAL above creates "<table>_id_seq" — alias it.
CREATE SEQUENCE IF NOT EXISTS statement_outbox_seq INCREMENT BY 50;
