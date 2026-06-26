-- ADR-0039 Phase A: read-only control-account ⇄ sub-ledger reconciliation audit trail.
-- Records each reconciliation run — per currency, the ledger deposit-control balance vs. the sum of
-- balance-service booked balances. This is a safety net that detects drift between the two
-- independent writers; it changes no balance. Phases B–D will make balance a true ledger projection
-- and retire the divergence this table watches for.
--
-- Rollback note:
--   DROP TABLE balance_reconciliation;
-- (Reversible: a pure audit table with no FK to or from balances/holds; dropping it loses only the
--  reconciliation history and the scheduled job's landing table — no money-path state is affected.)

CREATE TABLE balance_reconciliation (
    id                 BIGSERIAL PRIMARY KEY,
    as_of              DATE NOT NULL,
    generated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tolerance          NUMERIC(19,4) NOT NULL DEFAULT 0,
    has_drift          BOOLEAN NOT NULL,
    drifted_currencies VARCHAR(255) NOT NULL DEFAULT '',
    currencies         TEXT NOT NULL
);

CREATE INDEX idx_balance_reconciliation_generated_at ON balance_reconciliation(generated_at DESC);
