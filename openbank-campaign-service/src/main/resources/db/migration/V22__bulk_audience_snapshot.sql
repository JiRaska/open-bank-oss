-- SPDX-License-Identifier: Apache-2.0
-- ADR-0333 D3: no admission until a complete, durable audience extraction exists.
-- Rollback: hold all runs, retain snapshot and recipient evidence for the campaign retention
-- period, then remove this table and columns only after the associated runs are closed.
ALTER TABLE campaign_bulk_runs DROP CONSTRAINT campaign_bulk_runs_state_check;
ALTER TABLE campaign_bulk_runs ADD CONSTRAINT campaign_bulk_runs_state_check
    CHECK (state IN ('PREPARING', 'RUNNING', 'HELD', 'COMPLETED'));
ALTER TABLE campaign_bulk_runs ADD COLUMN snapshot_at TIMESTAMPTZ;
ALTER TABLE campaign_bulk_runs ADD COLUMN audience_count BIGINT;
ALTER TABLE campaign_bulk_runs ADD CONSTRAINT campaign_bulk_snapshot_complete
    CHECK ((snapshot_at IS NULL AND audience_count IS NULL) OR
           (snapshot_at IS NOT NULL AND audience_count IS NOT NULL AND audience_count >= 0));
DROP INDEX uq_campaign_bulk_live;
CREATE UNIQUE INDEX uq_campaign_bulk_live ON campaign_bulk_runs (campaign_id)
    WHERE state IN ('PREPARING', 'RUNNING', 'HELD');
CREATE INDEX idx_campaign_bulk_preparing ON campaign_bulk_runs (updated_at)
    WHERE state = 'PREPARING';

CREATE TABLE campaign_bulk_recipients (
    run_id UUID NOT NULL REFERENCES campaign_bulk_runs (id),
    party_id UUID NOT NULL,
    state TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'STARTING', 'ADMITTED', 'SKIPPED', 'FAILED')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, party_id)
);
CREATE INDEX idx_campaign_bulk_recipients_state ON campaign_bulk_recipients (run_id, state);
