-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0313 D9 — transactional outbox for risk-limit events (risk.limit.early-warning.v1 /
-- risk.limit.breach.v1), relayed to openbank.risk.limit.events by RiskOutboxDispatcher.
--
-- The limit EVALUATION itself is not stored: it is derived on read from the run. What must be
-- durable is that an event was emitted, exactly once per (run, limit, limit-set version) —
-- dedup_key is that natural key, so the EOD scheduler re-evaluating a replayed run, or two pods
-- ticking together, writes nothing new (INSERT ... ON CONFLICT (dedup_key) DO NOTHING). All rows of
-- one evaluation are inserted in a single transaction.
--
-- Same lifecycle columns as the fleet's Panache outbox (ADR-0050), on the plain reactive client.
-- synthetic is carried for the shared OutboxEntry contract (ADR-0252); limit events are produced by
-- the engine itself, never on behalf of a synthetic customer, so it is always false today.

CREATE TABLE risk_outbox (
    event_id        UUID PRIMARY KEY,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    payload         TEXT NOT NULL,
    dedup_key       VARCHAR(512) NOT NULL UNIQUE,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    attempt_count   INT NOT NULL DEFAULT 0,
    last_error      TEXT,
    claimed_at      TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    synthetic       BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT risk_outbox_status_known CHECK (status IN ('PENDING', 'DISPATCHING', 'SENT', 'FAILED', 'DEAD')),
    CONSTRAINT risk_outbox_created_at_plausible CHECK (created_at >= TIMESTAMPTZ '2020-01-01')
);

CREATE INDEX idx_risk_outbox_status ON risk_outbox (status, created_at);

-- Rollback (only while no application code still writes/reads risk_outbox, and after every row is
-- SENT — an unsent row is an alert nobody received):
--   DROP TABLE risk_outbox;
