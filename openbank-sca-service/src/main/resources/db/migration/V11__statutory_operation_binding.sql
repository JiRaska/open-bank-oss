-- Expand-only device-signature binding for a single immutable statutory delegation proposal.
-- Nullable for every existing challenge; retain the columns and evidence on application rollback.
ALTER TABLE sca_challenges ADD COLUMN IF NOT EXISTS dynamic_operation_id VARCHAR(36);
ALTER TABLE sca_challenges ADD COLUMN IF NOT EXISTS dynamic_operation_hash VARCHAR(64);
