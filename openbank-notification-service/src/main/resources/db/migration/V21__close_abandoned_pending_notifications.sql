-- Close PENDING rows that nothing will ever move to a terminal state.
--
-- NotificationOutcomeStuckPending (critical) fired continuously in sandbox on six rows that were
-- months old (measured 2026-11-10): five IN_APP rows from 2026-06-01 and one PUSH row from
-- 2026-07-17. IN_APP and SMS were removed as channels by #2372, so no adapter exists to finish
-- those rows, and nothing in this service retries a PENDING row of any channel. An alert that
-- is permanently red on historical data cannot report a NEW stuck notification, which is the
-- only thing it exists for.
--
-- Two classes, each closed as FAILED with a reason naming why, never as SENT:
--   channel_retired  - a channel this service no longer delivers on (any age).
--   outcome_unknown  - any other PENDING row older than 30 days. Nothing retries it, and a
--                      provider acceptance is not a delivery receipt (APNs issues none), so
--                      FAILED with this reason is the honest terminal state.
-- Rows younger than 30 days on a live channel are untouched, so the alert still sees them.
--
-- Rollback note: the pre-image is reproducible from this predicate. To revert,
--   UPDATE notifications SET status = 'PENDING', failure_reason = NULL
--   WHERE failure_reason IN ('channel_retired', 'outcome_unknown') AND sent_at IS NULL;
-- Nothing else reads failure_reason for these values, so reverting changes no other state.
UPDATE notifications
SET status = 'FAILED', failure_reason = 'channel_retired'
WHERE status = 'PENDING'
  AND channel NOT IN ('EMAIL', 'PUSH', 'INBOX');

UPDATE notifications
SET status = 'FAILED', failure_reason = 'outcome_unknown'
WHERE status = 'PENDING'
  AND created_at < now() - INTERVAL '30 days';
