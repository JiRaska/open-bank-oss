-- ADR-0252: preserve synthetic-origin taint across asynchronous outbox dispatch. The dispatcher
-- runs after the business transaction, often on another worker, so the persisted row is the
-- hand-off boundary — OutboxKafkaHeaders reconstructs the transport header from this value.
-- Rollback: before this migration is applied, DROP COLUMN synthetic; never edit an applied Flyway migration.
ALTER TABLE kyb_outbox ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;
