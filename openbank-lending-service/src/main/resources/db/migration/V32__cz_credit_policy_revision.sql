-- Each proposed payload is immutable. Approval assigns that exact payload to a future reference day.
-- Rollback: revert the reader and authoring routes first. Retain this table as decision evidence.
CREATE TABLE cz_credit_policy_revision (
    id UUID PRIMARY KEY,
    revision INTEGER NOT NULL CHECK (revision >= 3),
    effective_from DATE NOT NULL,
    payload TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PROPOSED', 'EXECUTED', 'REJECTED')),
    proposed_by TEXT NOT NULL,
    proposed_at TIMESTAMPTZ NOT NULL,
    decided_by TEXT,
    decided_at TIMESTAMPTZ,
    decision_reason TEXT,
    CHECK (
        (state = 'PROPOSED' AND decided_by IS NULL AND decided_at IS NULL)
        OR (state IN ('EXECUTED', 'REJECTED') AND decided_by IS NOT NULL
            AND decided_at IS NOT NULL AND decided_by <> proposed_by)
    )
);

CREATE UNIQUE INDEX uq_cz_policy_active_revision ON cz_credit_policy_revision (revision)
    WHERE state = 'EXECUTED';
CREATE UNIQUE INDEX uq_cz_policy_active_day ON cz_credit_policy_revision (effective_from)
    WHERE state = 'EXECUTED';
