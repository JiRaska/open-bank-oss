-- Bind pre-mode rail keys and mode-bound business releases in the same original-key namespace.
-- Rollback: revert the writer and retain the nullable digest and unique index as receipt evidence.
ALTER TABLE domestic_payments ADD COLUMN original_submission_key_digest VARCHAR(64)
    CHECK (original_submission_key_digest ~ '^[0-9a-f]{64}$');

-- The database encoding is fixed for a database. The result uses fixed UTF-8 bytes in the same
-- versioned domain as customer-edge, including any case, whitespace and backslashes in the key.
CREATE FUNCTION domestic_original_submission_digest(raw_key TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT PARALLEL SAFE
SET search_path = pg_catalog
RETURN encode(sha256(convert_to('openbank-domestic-approval-key-v1|' || raw_key, 'UTF8')), 'hex');

-- The existing rail idempotency key remains unique. This second fence prevents a different rail
-- key (for example an approval ID) from reusing an already submitted original key.
CREATE UNIQUE INDEX uq_domestic_payment_original_submission_key ON domestic_payments
    ((COALESCE(original_submission_key_digest, domestic_original_submission_digest(idempotency_key))));
