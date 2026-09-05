-- ADR-0252: preserve synthetic-origin taint across asynchronous outbox dispatch.
-- Rollback: before this migration is applied, DROP COLUMN synthetic; never edit an applied Flyway migration.
ALTER TABLE referral_outbox ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;
