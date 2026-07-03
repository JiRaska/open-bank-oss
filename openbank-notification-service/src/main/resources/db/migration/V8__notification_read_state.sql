-- Read-state for the customer notification center (app "mark read" / "mark all read").
-- NULL = unread; set once when the customer opens/acknowledges the notification.
-- Rollback: DROP INDEX idx_notifications_party_unread; ALTER TABLE notifications DROP COLUMN read_at;
ALTER TABLE notifications ADD COLUMN read_at TIMESTAMPTZ;

-- Unread-badge query path: count/list per party where read_at IS NULL.
CREATE INDEX idx_notifications_party_unread ON notifications (party_id) WHERE read_at IS NULL;
