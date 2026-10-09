-- A claimed idempotency key is not yet a completed customer receipt. Legacy rows stay
-- false because their screening/scheme decision cannot be reconstructed from status alone.
ALTER TABLE sct_inst_payments
    ADD COLUMN receipt_ready BOOLEAN NOT NULL DEFAULT FALSE;

-- Rollback: retain this additive column while reverting the application. Older releases
-- ignore it; dropping it before all readers are rolled back would erase decision evidence.
