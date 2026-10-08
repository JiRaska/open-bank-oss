-- Existing rows cannot be issuer-qualified from their request fingerprint; leave them unverifiable.
-- Rollback after reverting the reader/writer: ALTER TABLE domestic_payments DROP COLUMN receipt_actor_scope_hash;
ALTER TABLE domestic_payments ADD COLUMN receipt_actor_scope_hash VARCHAR(64);
ALTER TABLE domestic_payments ADD CONSTRAINT chk_domestic_receipt_actor_scope_hash
    CHECK (receipt_actor_scope_hash IS NULL OR receipt_actor_scope_hash ~ '^[0-9a-f]{64}$') NOT VALID;
