-- ADR-0252: carry the authenticated synthetic origin across the Temporal worker boundary.
-- Existing rows keep their real classification. Older binaries ignore this additive column.
-- Rollback before any synthetic transactions: DROP COLUMN synthetic.
-- After a synthetic transaction exists, retain the column and disable synthetic initiation
-- until all pending synthetic workflows are drained by a compatible worker.
ALTER TABLE transactions ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;
