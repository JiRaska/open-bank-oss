-- #13623: private booking clock; no historical start time is inferred or backfilled.
ALTER TABLE sepa_payments
    ADD COLUMN booking_first_due_at TIMESTAMPTZ,
    ADD COLUMN booking_proven_at TIMESTAMPTZ,
    ADD COLUMN booking_transaction_id UUID,
    ADD COLUMN booking_clock_covered BOOLEAN;
-- No DEFAULT: existing rows in every lifecycle state retain unknown coverage (NULL).
ALTER TABLE sepa_payments ADD CONSTRAINT sepa_booking_proof_complete CHECK (
    (booking_proven_at IS NULL AND booking_transaction_id IS NULL)
    OR (booking_proven_at IS NOT NULL AND booking_transaction_id IS NOT NULL)
);
CREATE INDEX idx_sepa_booking_unresolved_age ON sepa_payments (booking_first_due_at)
    WHERE booking_first_due_at IS NOT NULL AND booking_proven_at IS NULL;
-- Rollback: roll back readers/writers while retaining these additive columns and evidence.
-- Reconcile/export unresolved obligations and proven transaction identities before destructive removal.
-- Never truncate/drop this evidence or rotate booking keys merely to clear an unresolved clock.
-- Only after those prerequisites:
-- DROP INDEX idx_sepa_booking_unresolved_age;
-- ALTER TABLE sepa_payments DROP CONSTRAINT sepa_booking_proof_complete,
-- DROP COLUMN booking_first_due_at, DROP COLUMN booking_proven_at, DROP COLUMN booking_transaction_id, DROP COLUMN booking_clock_covered;
