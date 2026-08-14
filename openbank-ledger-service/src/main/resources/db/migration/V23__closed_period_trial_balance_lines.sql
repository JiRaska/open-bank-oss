-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0096 D1 / FINREP expand stage: immutable, line-level evidence for a FROZEN period.
-- Existing close rows remain readable and DRAFTs remain recomputable. New evidence is written
-- only together with the DRAFT -> FROZEN transition and its transactional-outbox event.
-- V22 FROZEN rows retain their valid hash anchors but are explicitly HASH_ONLY: they must never
-- be recomputed and presented as frozen line evidence.

ALTER TABLE ledger_closed_period
    ADD COLUMN evidence_state VARCHAR(16) NOT NULL DEFAULT 'HASH_ONLY',
    ADD CONSTRAINT chk_closed_period_evidence_state CHECK (evidence_state IN ('NONE', 'HASH_ONLY', 'LINES_V1'));

UPDATE ledger_closed_period SET evidence_state = 'NONE' WHERE status = 'DRAFT';

CREATE TABLE ledger_closed_period_trial_balance_line (
    period_id     UUID          NOT NULL REFERENCES ledger_closed_period(id) ON DELETE RESTRICT,
    gl_account_id UUID          NOT NULL,
    currency      VARCHAR(3)    NOT NULL,
    code          VARCHAR(128)  NOT NULL,
    name          VARCHAR(255)  NOT NULL,
    account_type  VARCHAR(32)   NOT NULL,
    total_debit   NUMERIC(20,6) NOT NULL,
    total_credit  NUMERIC(20,6) NOT NULL,
    PRIMARY KEY (period_id, gl_account_id, currency)
);

CREATE INDEX idx_closed_period_trial_balance_line_period
    ON ledger_closed_period_trial_balance_line (period_id, code, currency);

-- Evidence rows are append-only. The application never updates/deletes them, but the database
-- guard makes an accidental future repository change fail closed rather than rewrite attestation.
CREATE FUNCTION reject_closed_period_trial_balance_line_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_closed_period_trial_balance_line is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_closed_period_trial_balance_line_immutable
    BEFORE UPDATE OR DELETE ON ledger_closed_period_trial_balance_line
    FOR EACH ROW EXECUTE FUNCTION reject_closed_period_trial_balance_line_mutation();

-- Rollback (expand stage only): stop writers first, archive any frozen evidence that must remain
-- legally reproducible, then DROP TRIGGER, DROP FUNCTION and DROP TABLE. Do not roll back after
-- FINREP relies on this source unless the archive/read path has been validated.
