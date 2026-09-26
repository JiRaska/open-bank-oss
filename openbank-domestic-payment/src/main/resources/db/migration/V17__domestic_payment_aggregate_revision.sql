-- Strict per-payment ordering for replay-safe downstream lifecycle projections.
-- Existing aggregates predate the contract and start at revision 1; every emitted transition
-- increments the value while holding the payment row lock.
-- Rollback before a V17 writer runs: ALTER TABLE domestic_payments DROP COLUMN aggregate_revision;

ALTER TABLE domestic_payments
    ADD COLUMN aggregate_revision BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT chk_domestic_payment_aggregate_revision CHECK (aggregate_revision > 0);
