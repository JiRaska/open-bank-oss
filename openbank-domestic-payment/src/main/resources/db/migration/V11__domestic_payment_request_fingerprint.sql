-- Bind every newly created payment's Idempotency-Key to the complete normalized request and actor.
-- Existing rows remain NULL deliberately: the service fails their replay closed with 409 because it
-- cannot prove what request originally created them.
--
-- The column add is metadata-only (no default/backfill). The constraint is installed NOT VALID in
-- this migration and validated only in V12: Flyway holds transaction locks until each migration
-- commits, so placing VALIDATE here would keep ADD COLUMN's ACCESS EXCLUSIVE lock for the scan.
--
-- Rollback (deploy code that no longer reads/writes the column first):
--   ALTER TABLE domestic_payments DROP CONSTRAINT chk_domestic_payments_request_fingerprint;
--   ALTER TABLE domestic_payments DROP COLUMN request_fingerprint;

ALTER TABLE domestic_payments
    ADD COLUMN request_fingerprint VARCHAR(64);

ALTER TABLE domestic_payments
    ADD CONSTRAINT chk_domestic_payments_request_fingerprint
        CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$') NOT VALID;
