-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0342 D1 / issue #13167 — day-close participation reports.
--
-- Each calendar-driven product service (interest first) reports, per accounting day, whether it
-- RAN (and how many journals it booked), SKIPPED (with a reason) or was REFUSED because the day no
-- longer accepted postings; an operator may WAIVE a missing participant. AccountingDayScheduler
-- reads the latest row per (business_date, participant) before CUTOFF -> TIED_OUT.
--
-- Append-only history: a re-report adds a row, the latest wins on read. No FK to
-- ledger_accounting_day on purpose — a participant may report a day before the scheduler has
-- opened it (the first tick after midnight), and the report must not be lost for that race.
--
-- NEW table only; purely additive and online-safe.

CREATE TABLE ledger_close_participation (
    id               UUID PRIMARY KEY,
    business_date    DATE         NOT NULL,
    participant      VARCHAR(64)  NOT NULL,
    outcome          VARCHAR(16)  NOT NULL
        CONSTRAINT chk_close_participation_outcome CHECK (outcome IN ('RAN', 'SKIPPED', 'REFUSED', 'WAIVED')),
    journals_posted  INTEGER      NOT NULL DEFAULT 0 CHECK (journals_posted >= 0),
    items_processed  INTEGER      NOT NULL DEFAULT 0 CHECK (items_processed >= 0),
    reason           VARCHAR(1000),
    reported_by      VARCHAR(255) NOT NULL,
    reported_at      TIMESTAMPTZ  NOT NULL,
    late             BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Insertion order, the ONLY definition of "latest": two reports can carry the same instant, and a
    -- clock is not an ordering. Rows are appended under a per-day advisory lock, so seq order is the
    -- true order of decisions.
    seq              BIGINT       GENERATED ALWAYS AS IDENTITY,
    CONSTRAINT chk_close_participation_reason CHECK (outcome = 'RAN' OR reason IS NOT NULL)
);

CREATE INDEX idx_close_participation_day
    ON ledger_close_participation (business_date, participant, seq DESC);

-- Rollback: revert the feature while retaining this additive table and its audit history.
-- Shadow mode does not make recorded reports, operator identities or waivers disposable.
-- Schema removal requires a separate reviewed retention/rollback plan; deleting the table loses
-- evidence and breaks participant reads/checks until the schema and records are restored.
