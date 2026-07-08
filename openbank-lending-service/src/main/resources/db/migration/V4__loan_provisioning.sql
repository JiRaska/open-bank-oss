-- SPDX-License-Identifier: Apache-2.0
-- IFRS 9 provisioning cycle (ADR-0028 Phase 3): one stage/ECL record per loan per reporting period.
-- The scheduled provisioning pass reads the prior period's row (ORDER BY period DESC LIMIT 1, WHERE
-- period < :current) as the delta baseline, then inserts the new period's row. UNIQUE(loan_id, period)
-- is both the natural key and the idempotency guard for a re-run of an already-provisioned period.
--
-- No <table>_seq sequence: like loan/loan_application/installment/collateral, the entity is a
-- PanacheEntityBase with a client-generated UUID id (Hibernate never allocates from a DB sequence for
-- it), unlike lending_outbox's BIGSERIAL id (V3). HibernateSequenceGuardTest only requires a sequence
-- for `: PanacheEntity()` (numeric-id) entities, which this is not.
--
-- Rollback: DROP TABLE loan_provisioning;

CREATE TABLE loan_provisioning (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id               UUID NOT NULL REFERENCES loan(id),
    period                VARCHAR(7) NOT NULL,            -- reporting period key, "yyyy-MM"
    as_of                 DATE NOT NULL,
    outstanding_balance   NUMERIC(20,2) NOT NULL,
    currency              CHAR(3) NOT NULL,
    days_past_due         INTEGER NOT NULL,
    bucket                VARCHAR(16) NOT NULL,           -- Delinquency.DelinquencyBucket name
    stage                 VARCHAR(16) NOT NULL,           -- Ifrs9Stage name (STAGE_1/2/3)
    expected_credit_loss  NUMERIC(20,2) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(loan_id, period)
);

-- Drives findLatestBefore (the delta baseline read) and findByLoanAndPeriod (the idempotency check).
CREATE INDEX idx_loan_provisioning_loan_period ON loan_provisioning(loan_id, period DESC);
