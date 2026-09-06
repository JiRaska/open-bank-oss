-- A producer can attach one durable business-fact key to a notification request. The partial
-- unique index makes at-least-once Kafka delivery safe without changing legacy requests, which
-- leave the key NULL. The first use of a delegation is the initial caller.
--
-- Rollback: DROP INDEX IF EXISTS uq_notifications_deduplication_key;
--           ALTER TABLE notifications DROP COLUMN IF EXISTS deduplication_key;
ALTER TABLE notifications ADD COLUMN deduplication_key UUID;

CREATE UNIQUE INDEX uq_notifications_deduplication_key
    ON notifications (deduplication_key)
    WHERE deduplication_key IS NOT NULL;
