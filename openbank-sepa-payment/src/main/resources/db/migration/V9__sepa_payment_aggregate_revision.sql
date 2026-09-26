-- Source-issued ordering for replay-safe status and return evidence. Existing aggregates begin at
-- revision zero; each subsequent transition persists its increment with the corresponding outbox.
-- Rollback: after rolling back code that reads aggregate_revision, ALTER TABLE sepa_payments DROP COLUMN aggregate_revision;
ALTER TABLE sepa_payments
    ADD COLUMN aggregate_revision BIGINT NOT NULL DEFAULT 0;
