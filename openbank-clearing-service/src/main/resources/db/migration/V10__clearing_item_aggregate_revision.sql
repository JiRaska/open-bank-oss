-- Monotonic source ordering for replay-safe item-cleared evidence. Existing rows begin at zero;
-- each subsequent state transition increments the value in the same transaction as the row update.
-- Rollback: after rolling back code that reads aggregate_revision, ALTER TABLE clearing_items DROP COLUMN aggregate_revision;
ALTER TABLE clearing_items
    ADD COLUMN aggregate_revision BIGINT NOT NULL DEFAULT 0;
