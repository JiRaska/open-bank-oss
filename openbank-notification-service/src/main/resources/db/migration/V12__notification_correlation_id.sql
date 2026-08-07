-- ADR-0239 D1: a producer-supplied correlation id, echoed unchanged onto every outcome event for
-- this request. Nullable and with no default — most producers never set one, and "absent" must stay
-- distinguishable from "set to something".
--
-- Its own column rather than a key inside the existing `metadata` JSONB: `metadata` is not mapped on
-- NotificationEntity at all, so using it would have meant adding a JSONB converter to write one
-- scalar, and a JSONB key cannot be indexed as cheaply as this lookup wants.
--
-- ROLLBACK: DROP INDEX IF EXISTS idx_notifications_correlation_id;
--           ALTER TABLE notifications DROP COLUMN IF EXISTS correlation_id;
-- Safe in both directions — the column is nullable, nothing reads it as a key, and no existing row
-- carries a value, so dropping it loses only correlations recorded after this migration ran.
ALTER TABLE notifications ADD COLUMN correlation_id UUID;

-- Partial index: the overwhelming majority of rows have no correlation id (account, onboarding,
-- SCA), and only the correlated ones are ever looked up this way.
CREATE INDEX idx_notifications_correlation_id
    ON notifications (correlation_id)
    WHERE correlation_id IS NOT NULL;
