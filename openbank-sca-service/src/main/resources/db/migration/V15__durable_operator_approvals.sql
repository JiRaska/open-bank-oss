-- Rollback: retain approval and outbox evidence. Pause governed mutations and drain all live
-- approvals before replacing every writer. Redis has no copy of PostgreSQL approvals.
-- Do not restore a Redis snapshot to resurrect an already consumed approval.
CREATE TABLE sca_operator_approvals (
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
CREATE INDEX idx_sca_operator_approvals_pending ON sca_operator_approvals (expires_at, created_at, id)
    WHERE status = 'PENDING';
