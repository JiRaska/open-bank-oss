-- Source-issued ordering for replay-safe status and return evidence. Existing aggregates begin at
-- revision zero; each subsequent transition persists its increment with the corresponding outbox.
ALTER TABLE sepa_payments
    ADD COLUMN aggregate_revision BIGINT NOT NULL DEFAULT 0;
