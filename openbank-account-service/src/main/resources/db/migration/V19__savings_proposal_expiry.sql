-- Expiry window on propose-only withdrawal proposals (ADR-0232 D8 / issue #2990 AC8).
-- Rollback:
--   DROP INDEX idx_proposals_pending_expiry;
--   ALTER TABLE savings_withdrawal_proposals DROP COLUMN expires_at;
--   (the EXPIRED status value is application-side only; no enum type to revert)

-- Backfill: proposals written before this migration have no window. NOW() + 7 days gives the
-- existing PENDING ones the same TTL a fresh proposal gets, rather than expiring them instantly
-- (created_at + 7 days would retroactively kill anything older than a week, which is a silent
-- state change on live rows nobody asked for).
ALTER TABLE savings_withdrawal_proposals
    ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '7 days';

-- The default was only needed to backfill; new rows always state their own window.
ALTER TABLE savings_withdrawal_proposals ALTER COLUMN expires_at DROP DEFAULT;

-- The expiry sweep's access path: PENDING rows ordered by window close.
CREATE INDEX idx_proposals_pending_expiry
    ON savings_withdrawal_proposals (expires_at)
    WHERE status = 'PENDING';

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
