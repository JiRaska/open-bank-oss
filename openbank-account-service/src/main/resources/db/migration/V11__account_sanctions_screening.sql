-- V11: Record sanctions screening result at account opening (ADR-0032 §C).
-- Rollback: ALTER TABLE accounts DROP COLUMN sanctions_screened_at, DROP COLUMN sanctions_status;
ALTER TABLE accounts ADD COLUMN sanctions_screened_at TIMESTAMPTZ;
ALTER TABLE accounts ADD COLUMN sanctions_status      VARCHAR(20);
