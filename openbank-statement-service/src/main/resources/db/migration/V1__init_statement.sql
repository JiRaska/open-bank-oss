-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0035: account statements. We persist ONLY the period-close record (metadata + sequence +
-- balance anchors) and the outbox — never rendered camt.053/MT940/PDF bytes. Renders are produced
-- on demand from this record + booked entries replayed from transaction-service (ADR-0035 §F).
--
-- Rollback: DROP TABLE statement_outbox; DROP TABLE statement_period;

CREATE TABLE statement_period (
    id                          UUID            PRIMARY KEY,
    account_id                  UUID            NOT NULL,
    pocket_currency             VARCHAR(3)      NOT NULL,
    period_from                 DATE            NOT NULL,
    period_to                   DATE            NOT NULL,
    legal_sequence_number       BIGINT          NOT NULL,
    electronic_sequence_number  BIGINT          NOT NULL,
    opening_balance             NUMERIC(23,4)   NOT NULL,
    closing_balance             NUMERIC(23,4)   NOT NULL,
    entry_count                 INTEGER         NOT NULL DEFAULT 0,
    status                      VARCHAR(16)     NOT NULL DEFAULT 'CLOSED',
    supersedes_sequence         BIGINT,
    closed_at                   TIMESTAMPTZ     NOT NULL
);

-- Idempotency (ADR-0035 §F): one close per (account, pocket, period).
CREATE UNIQUE INDEX ux_statement_period_window
    ON statement_period (account_id, pocket_currency, period_from, period_to);

-- Monotonic legal sequence per pocket (camt.053/MT940 statement page).
CREATE UNIQUE INDEX ux_statement_period_legal_seq
    ON statement_period (account_id, pocket_currency, legal_sequence_number);

CREATE INDEX ix_statement_period_account
    ON statement_period (account_id, period_to DESC);

CREATE TABLE statement_outbox (
    id              UUID            PRIMARY KEY,
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
