-- Persist WHY a notification ended FAILED.
--
-- The reason has always been computed (NotificationConsumer emits REASON_NO_DEVICE /
-- REASON_PUSH_REJECTED / the consent reasons on the NotificationOutcome outbox event) but was
-- never written to the row, so the table could only say FAILED. On 2026-08-08 that made a real
-- question unanswerable from the database: 53 of 66 PUSH notifications were FAILED, and telling
-- "this party has no registered device" apart from "APNs rejected the token" required reading
-- outbox payloads that are pruned after dispatch.
--
-- Nullable and unconstrained on purpose: SENT rows carry no reason, and the vocabulary lives in
-- NotificationOutcomeEvent rather than in a DB enum that would need a migration per new reason.
--
-- ROLLBACK: ALTER TABLE notifications DROP COLUMN failure_reason;
ALTER TABLE notifications
    ADD COLUMN failure_reason VARCHAR(64);

COMMENT ON COLUMN notifications.failure_reason IS
    'Terminal failure reason (NotificationOutcomeEvent.REASON_*); NULL for SENT/PENDING rows.';
