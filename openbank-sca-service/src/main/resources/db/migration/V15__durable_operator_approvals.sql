-- Durable SCA maker/checker approvals (ADR-0155). Each state transition commits together with an
-- SCA_OPERATOR_APPROVAL_CHANGED outbox row, so maker, checker and claim are retained evidence.
-- request_fingerprint/summary carry the shared request binding (#11675): only a retry of the exact
-- paused request may claim an approval; a NULL fingerprint never satisfies an intercepted request.
--
-- Rollback: retain this table and the outbox evidence (do not DROP). Pause governed SCA mutations
-- and let live approvals drain or expire before replacing writers. Redis holds no copy of these
-- approvals; never restore a Redis snapshot to resurrect an already consumed approval. A rolled-back
-- binary without PostgresApprovalStore simply stops reading the table.
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
    request_fingerprint VARCHAR(64),
    summary VARCHAR(1024),
    CHECK (expires_at > created_at),
    CHECK ((status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decided_by <> maker_id)),
    CHECK ((status = 'EXECUTED') = (claimed_at IS NOT NULL))
);

CREATE INDEX idx_sca_operator_approvals_pending ON sca_operator_approvals (expires_at, created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_sca_operator_approvals_maker_pending ON sca_operator_approvals (action, maker_id, expires_at)
    WHERE status = 'PENDING';
