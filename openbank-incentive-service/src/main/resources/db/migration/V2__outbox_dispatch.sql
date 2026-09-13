-- Rollback: stop the dispatcher, then drop the added columns. Rows remain valid audit evidence,
-- but no broker delivery state can be reconstructed after rollback.
ALTER TABLE incentive_outbox
  ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN claimed_at TIMESTAMPTZ,
  ADD COLUMN claim_token UUID,
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN last_error TEXT,
  ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX incentive_outbox_dispatch_idx
  ON incentive_outbox(status, occurred_at);
