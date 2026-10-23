-- #13686: durable, bounded re-drive of an accepted MT103 whose settlement booking is unproven.
-- Bookkeeping only: the booking clock (V11) still decides WHAT is owed; these columns decide WHEN the
-- next attempt may run and WHO holds it. Additive, nullable or defaulted; no existing row changes meaning.
ALTER TABLE swift_messages
    ADD COLUMN booking_redrive_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN booking_redrive_next_at TIMESTAMPTZ,
    ADD COLUMN booking_redrive_claim UUID,
    ADD COLUMN booking_redrive_claimed_until TIMESTAMPTZ,
    ADD COLUMN booking_redrive_exhausted_at TIMESTAMPTZ,
    -- Manual-remediation stop: once set, the sweep never claims the message again. Set FIRST, under
    -- four-eyes, before any hand booking or reversal (runbook 0032), and by operator ack/reject.
    ADD COLUMN booking_redrive_stopped_at TIMESTAMPTZ,
    ADD COLUMN booking_redrive_stop_reason TEXT,
    -- Four-eyes "no booking owed" close (runbook 0032 only, never written by the service): e.g. a message
    -- rejected after ACSC whose debit is not owed. A waived message leaves the age gauge and the alert.
    ADD COLUMN booking_waived_at TIMESTAMPTZ,
    ADD COLUMN booking_waive_reason TEXT,
    ADD COLUMN booking_waive_approver TEXT;
ALTER TABLE swift_messages ADD CONSTRAINT swift_booking_waive_complete CHECK (
    (booking_waived_at IS NULL AND booking_waive_reason IS NULL AND booking_waive_approver IS NULL)
    OR (booking_waived_at IS NOT NULL AND length(trim(booking_waive_reason)) > 0
        AND length(trim(booking_waive_approver)) > 0)
);
ALTER TABLE swift_messages ADD CONSTRAINT swift_booking_redrive_stop_reasoned CHECK (
    booking_redrive_stopped_at IS NULL OR booking_redrive_stop_reason IS NOT NULL
);
-- A claim is a token plus its lease, never one without the other.
ALTER TABLE swift_messages ADD CONSTRAINT swift_booking_redrive_claim_complete CHECK (
    (booking_redrive_claim IS NULL AND booking_redrive_claimed_until IS NULL)
    OR (booking_redrive_claim IS NOT NULL AND booking_redrive_claimed_until IS NOT NULL)
);
CREATE INDEX idx_swift_booking_redrive_due ON swift_messages (booking_redrive_next_at)
    WHERE booking_first_due_at IS NOT NULL AND booking_proven_at IS NULL
      AND booking_redrive_exhausted_at IS NULL AND booking_redrive_stopped_at IS NULL
      AND booking_waived_at IS NULL;
-- Rollback: disable the sweep first (openbank.swift.settlement-redrive.enabled=false) and roll back the
-- reader/writer; the columns are inert without it and may stay. Never touch booking_first_due_at,
-- booking_proven_at or booking_transaction_id to roll this back. Only after that:
-- DROP INDEX idx_swift_booking_redrive_due;
-- ALTER TABLE swift_messages DROP CONSTRAINT swift_booking_redrive_claim_complete,
-- DROP CONSTRAINT swift_booking_redrive_stop_reasoned, DROP COLUMN booking_redrive_stopped_at,
-- DROP COLUMN booking_redrive_stop_reason, DROP CONSTRAINT swift_booking_waive_complete,
-- DROP COLUMN booking_waived_at, DROP COLUMN booking_waive_reason, DROP COLUMN booking_waive_approver,
-- DROP COLUMN booking_redrive_attempts, DROP COLUMN booking_redrive_next_at, DROP COLUMN booking_redrive_claim,
-- DROP COLUMN booking_redrive_claimed_until, DROP COLUMN booking_redrive_exhausted_at;
