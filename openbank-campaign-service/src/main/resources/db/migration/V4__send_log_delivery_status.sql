-- ADR-0239 D3 (issue #3663): the send log records a HANDOFF in `outcome`, which is all this service
-- can observe for itself. What became of the message is reported back by notification-service on
-- `openbank.notification.outcomes.v1` and lands here.
--
-- `outcome` is deliberately left alone. It is the frequency cap's input (the cap counts SENT rows,
-- ADR-0219 D2) and renaming or repurposing it would silently change what a cap means.
--
-- DEFAULT 'PENDING' backfills every existing row: "no outcome has arrived" is exactly true of them,
-- since nothing has ever published one. It is not a claim that they failed.
--
-- ROLLBACK:
--   DROP INDEX IF EXISTS idx_send_log_delivery_status;
--   ALTER TABLE send_log DROP COLUMN IF EXISTS delivery_updated_at;
--   ALTER TABLE send_log DROP COLUMN IF EXISTS delivery_reason;
--   ALTER TABLE send_log DROP COLUMN IF EXISTS delivery_status;
-- Additive only, so the rollback loses the reported outcomes and nothing else; `outcome` and the
-- cap that reads it are untouched in both directions.
ALTER TABLE send_log ADD COLUMN delivery_status     TEXT NOT NULL DEFAULT 'PENDING';
ALTER TABLE send_log ADD COLUMN delivery_reason     TEXT;
ALTER TABLE send_log ADD COLUMN delivery_updated_at TIMESTAMPTZ;

-- Supports the per-campaign funnel's delivery column; the console never asks for a delivery status
-- without naming a campaign.
CREATE INDEX idx_send_log_delivery_status ON send_log (campaign_id, delivery_status);
