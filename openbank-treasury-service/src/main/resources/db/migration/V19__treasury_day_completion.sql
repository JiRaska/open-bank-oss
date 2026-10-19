-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 D7 / contract 6: the per-accounting-day completion record. Each treasury run writes the
-- outcome of its step for the bank day it covered (FIXINGS, ACCRUALS, PAYMENTS, REVALUATION,
-- CORRECTIONS, MATURITIES); a later run of the same step on the same day replaces the row, so the
-- record always states the last outcome. The day-end close reads it through
-- GET /api/v1/treasury/day-completion/{date}; a step with no row has not run that day.
--
-- Rollback: DROP TABLE treasury_day_completion; DROP SEQUENCE treasury_day_completion_seq;
CREATE SEQUENCE IF NOT EXISTS treasury_day_completion_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE treasury_day_completion (
    id             BIGINT        PRIMARY KEY,
    business_date  DATE          NOT NULL,
    step           VARCHAR(16)   NOT NULL
        CHECK (step IN ('FIXINGS', 'ACCRUALS', 'PAYMENTS', 'REVALUATION', 'CORRECTIONS', 'MATURITIES')),
    status         VARCHAR(8)    NOT NULL CHECK (status IN ('DONE', 'SKIPPED', 'FAILED', 'PENDING')),
    item_count     INT           CHECK (item_count IS NULL OR item_count >= 0),
    detail         VARCHAR(500),
    recorded_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT treasury_day_completion_step UNIQUE (business_date, step)
);
