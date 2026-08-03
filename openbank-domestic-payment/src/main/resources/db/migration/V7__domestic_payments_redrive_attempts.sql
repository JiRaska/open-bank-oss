-- #3266: a payment held fail-closed on an unavailable sanctions screen had no way out.
--
-- The Temporal workflow returns RECEIVED and COMPLETES; the AML case is opened OPEN and nothing
-- consumes its decision (domestic-payment has no messaging consumer at all), so a dependency
-- outage lasting minutes became a permanent strand — one payment sat hours, others six weeks.
-- Holding is correct: on an outage a HIT is unknowable, and applySddPolicy only ever downgrades
-- REVIEW, never BLOCK. What was missing is something that re-opens the hold.
--
-- This counter bounds the re-drive sweep that now does. Without it the sweep would either
-- re-screen a genuinely-held payment forever, or need an age window loose enough to be useless.
-- Counting attempts makes the give-up point explicit and auditable on the row itself, which on a
-- money-path table is worth a column.
--
-- ROLLBACK: ALTER TABLE domestic_payments DROP COLUMN redrive_attempts;
-- Safe — nothing outside the sweep reads it, and dropping it only disables the re-drive bound.
ALTER TABLE domestic_payments
    ADD COLUMN redrive_attempts INTEGER NOT NULL DEFAULT 0;

-- Partial: the sweep only ever looks at non-terminal rows under the cap, which is a tiny slice of
-- a table that is mostly settled.
CREATE INDEX idx_domestic_payments_redrivable
    ON domestic_payments (created_at)
    WHERE status = 'RECEIVED';
