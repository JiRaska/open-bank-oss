-- #13686: durable, bounded re-drive of an accepted MT103 whose settlement booking is unproven.
-- Bookkeeping only: the booking clock (V11) still decides WHAT is owed; these columns decide WHEN the
-- next attempt may run and WHO holds it. Additive, nullable or defaulted; no existing row changes meaning.
ALTER TABLE swift_messages
    ADD COLUMN booking_redrive_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN booking_redrive_next_at TIMESTAMPTZ,
    ADD COLUMN booking_redrive_claim UUID,
    ADD COLUMN booking_redrive_claimed_until TIMESTAMPTZ,
    ADD COLUMN booking_redrive_exhausted_at TIMESTAMPTZ;
-- A claim is a token plus its lease, never one without the other.
ALTER TABLE swift_messages ADD CONSTRAINT swift_booking_redrive_claim_complete CHECK (
    (booking_redrive_claim IS NULL AND booking_redrive_claimed_until IS NULL)
    OR (booking_redrive_claim IS NOT NULL AND booking_redrive_claimed_until IS NOT NULL)
);
CREATE INDEX idx_swift_booking_redrive_due ON swift_messages (booking_redrive_next_at)
    WHERE booking_first_due_at IS NOT NULL AND booking_proven_at IS NULL
      AND booking_redrive_exhausted_at IS NULL;
-- Rollback: disable the sweep first (openbank.swift.settlement-redrive.enabled=false) and roll back the
-- reader/writer; the columns are inert without it and may stay. Never touch booking_first_due_at,
-- booking_proven_at or booking_transaction_id to roll this back. Only after that:
-- DROP INDEX idx_swift_booking_redrive_due;
-- ALTER TABLE swift_messages DROP CONSTRAINT swift_booking_redrive_claim_complete,
-- DROP COLUMN booking_redrive_attempts, DROP COLUMN booking_redrive_next_at, DROP COLUMN booking_redrive_claim,
-- DROP COLUMN booking_redrive_claimed_until, DROP COLUMN booking_redrive_exhausted_at;
