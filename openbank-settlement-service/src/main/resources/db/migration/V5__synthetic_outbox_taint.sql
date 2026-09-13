-- Additive shared outbox provenance column. Retain during rollback.
ALTER TABLE settlement_outbox ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;
