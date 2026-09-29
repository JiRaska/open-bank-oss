-- Strict monotonic ordering for complaint projections. Existing rows represent revision 1;
-- subsequent lifecycle mutations increment under a pessimistic row lock in the same transaction
-- that appends the complaint event to dispute_outbox.
-- Rollback before strict consumers are enabled: ALTER TABLE complaints DROP COLUMN aggregate_revision;

ALTER TABLE complaints
    ADD COLUMN aggregate_revision BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT chk_complaints_aggregate_revision CHECK (aggregate_revision > 0);
