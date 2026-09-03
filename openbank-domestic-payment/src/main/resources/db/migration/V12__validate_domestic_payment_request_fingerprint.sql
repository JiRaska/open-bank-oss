-- Validate V11's already-enforced CHECK in its own Flyway transaction. PostgreSQL validation uses
-- SHARE UPDATE EXCLUSIVE instead of retaining V11's ACCESS EXCLUSIVE ADD COLUMN lock for the scan.
-- Existing rows are allowed to remain NULL deliberately; the application rejects those replays.
--
-- Rollback: no action. This changes only the constraint's validated flag; V11 owns its removal.

ALTER TABLE domestic_payments
    VALIDATE CONSTRAINT chk_domestic_payments_request_fingerprint;
