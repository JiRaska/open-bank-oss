-- A producer can attach one durable business-fact key to a notification request. The partial
-- unique index makes at-least-once Kafka delivery safe without changing legacy requests, which
-- leave the key NULL. The first use of a delegation is the initial caller.
--
-- History: this DDL first reached main as V14__notification_deduplication_key.sql (#8334), but
-- V14__synthetic_outbox_taint.sql (#6731) had already claimed version 14. Flyway refuses to
-- resolve two migrations with the same version, so no pod carrying both files could ever boot —
-- which is also the proof that the V14 copy was never applied to any live database, and why
-- renumbering rewrites no applied history (#8961; the db-migration gate blocks in-place renames
-- of committed migrations, so the V14 file was deleted and this one added instead).
--
-- The IF NOT EXISTS guards make a re-run against a database that received this DDL out of band
-- (e.g. a manually repaired environment) converge instead of failing.
--
-- Rollback: DROP INDEX IF EXISTS uq_notifications_deduplication_key;
--           ALTER TABLE notifications DROP COLUMN IF EXISTS deduplication_key;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS deduplication_key UUID;

CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_deduplication_key
    ON notifications (deduplication_key)
    WHERE deduplication_key IS NOT NULL;
