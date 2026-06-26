-- Idempotency ledger for the DIRECT credit/debit money-movement path (BalanceUseCase.credit/debit),
-- the path the transaction saga uses to move value between pockets. Unlike the ledger projection
-- (V6, ADR-0039 Phase D), the direct path had NO dedup: a retried credit/debit with the same
-- referenceId double-applied — a real money bug under at-least-once delivery / client retries.
--
-- One row per applied movement, keyed by the (account, currency, referenceId, operation) the caller
-- supplies. The marker is written in the SAME transaction as the balance mutation, so a redelivery
-- that finds the row present is skipped — the booked/available amounts move exactly once.
--
-- Rollback note:
--   DROP TABLE balance_movement;
-- (Reversible: a pure dedup/audit table with no FK to or from balances/holds. Dropping it only loses
--  the applied-movement history and re-opens the double-apply window; it does not corrupt balances.
--  NB: after a drop, an in-flight retry of an already-applied movement could re-apply — quiesce the
--  saga before dropping.)

CREATE TABLE balance_movement (
    account_id   UUID          NOT NULL,
    currency     VARCHAR(3)    NOT NULL,
    reference_id VARCHAR(200)  NOT NULL,
    operation    VARCHAR(8)    NOT NULL,
    delta        NUMERIC(19,4) NOT NULL,
    applied_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, currency, reference_id, operation),
    CONSTRAINT chk_balance_movement_operation CHECK (operation IN ('CREDIT', 'DEBIT'))
);

CREATE INDEX idx_balance_movement_ref ON balance_movement(reference_id);
