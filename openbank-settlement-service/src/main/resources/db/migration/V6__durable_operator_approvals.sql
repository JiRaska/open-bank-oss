-- Before migration, pause outbox dispatch on old binaries using RETURNING *; generated columns
-- are not mapped by those binaries. Resume only after all dispatchers use the explicit projection.
-- Rollback: disable new governed originations and retain approval/outbox evidence.
-- Drain or expire live approvals before replacing writers; never resurrect consumed approvals.
CREATE TABLE settlement_operator_approvals (
    id UUID PRIMARY KEY,
    action TEXT NOT NULL,
    resource_id TEXT,
    maker_id TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXECUTED')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    decided_by TEXT,
    decided_at TIMESTAMPTZ,
    claimed_at TIMESTAMPTZ,
    CHECK (expires_at > created_at),
    CHECK ((status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decided_by <> maker_id)),
    CHECK ((status = 'EXECUTED') = (claimed_at IS NOT NULL))
);
CREATE INDEX idx_settlement_operator_approvals_pending ON settlement_operator_approvals (expires_at, created_at, id)
    WHERE status = 'PENDING';

-- Preserve referential integrity for both aggregate types on the existing delivery queue.
-- Existing inserts need no new values; callers cannot choose a different FK target.
-- Dispatcher compatibility still requires the explicit projection described above.
ALTER TABLE settlement_outbox
    ADD COLUMN settlement_ref UUID GENERATED ALWAYS AS
        (CASE WHEN event_type = 'SETTLEMENT_STATE_CHANGED' THEN aggregate_id END) STORED
        REFERENCES settlements(id),
    ADD COLUMN approval_ref UUID GENERATED ALWAYS AS
        (CASE WHEN event_type = 'SETTLEMENT_OPERATOR_APPROVAL_CHANGED' THEN aggregate_id END) STORED
        REFERENCES settlement_operator_approvals(id),
    ADD CONSTRAINT settlement_outbox_known_aggregate CHECK
        (event_type IN ('SETTLEMENT_STATE_CHANGED', 'SETTLEMENT_OPERATOR_APPROVAL_CHANGED'));
ALTER TABLE settlement_outbox DROP CONSTRAINT settlement_outbox_aggregate_id_fkey;
