-- Rollback: retain this additive column and its provenance values when reverting binaries.
-- Additive shared outbox provenance column. Retain during rollback.
ALTER TABLE settlement_outbox ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;
