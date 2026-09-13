-- Durable maker-checker evidence for bank-side delegation lifecycle actions.
--
-- Integration order: apply only after lifecycle V8-V10 and spend V11-V12. The row is monotonic:
-- proposal fields are inserted once, and a decision may move PROPOSED to REJECTED or EXECUTED
-- exactly once. APPROVED is deliberately absent, so the database never promises an approved
-- action that did not happen. The current-main application creates PROPOSED/REJECTED only;
-- EXECUTED remains fail-closed until the revisioned lifecycle CAS/event seam is integrated.
--
-- X-Request-ID is a database uniqueness fact. Retrying proposal creation returns the same row;
-- reusing the key for a different action is rejected by the application.
--
-- Rollback: keep the mutation flag false and revert the application images first (older binaries
-- ignore this additive table). If permanent removal is required, drop the table in a new forward
-- migration; never edit this migration after Flyway has applied it.
CREATE TABLE delegation_lifecycle_approvals (
    id                  UUID PRIMARY KEY,
    delegation_id       UUID NOT NULL REFERENCES delegation_grants(id),
    operation           VARCHAR(16) NOT NULL,
    requested_reason    VARCHAR(500) NOT NULL,
    request_key         VARCHAR(200) NOT NULL,
    proposed_by         VARCHAR(200) NOT NULL,
    proposed_at         TIMESTAMPTZ NOT NULL,
    state               VARCHAR(16) NOT NULL DEFAULT 'PROPOSED',
    decided_by          VARCHAR(200),
    decided_at          TIMESTAMPTZ,
    decision_reason     VARCHAR(500),
    executed_at         TIMESTAMPTZ,
    CONSTRAINT uq_delegation_lifecycle_request_key UNIQUE (request_key),
    CONSTRAINT chk_delegation_lifecycle_operation
        CHECK (operation IN ('SUSPEND', 'REINSTATE', 'REVOKE')),
    CONSTRAINT chk_delegation_lifecycle_state
        CHECK (state IN ('PROPOSED', 'REJECTED', 'EXECUTED')),
    CONSTRAINT chk_delegation_lifecycle_reason
        CHECK (length(trim(requested_reason)) > 0),
    CONSTRAINT chk_delegation_lifecycle_identifiers CHECK (
        length(trim(request_key)) > 0
        AND length(trim(proposed_by)) > 0
        AND (decided_by IS NULL OR length(trim(decided_by)) > 0)
    ),
    CONSTRAINT chk_delegation_lifecycle_evidence CHECK (
        (state = 'PROPOSED' AND decided_by IS NULL AND decided_at IS NULL
            AND decision_reason IS NULL AND executed_at IS NULL)
        OR
        (state = 'REJECTED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decision_reason IS NOT NULL AND length(trim(decision_reason)) > 0 AND executed_at IS NULL)
        OR
        (state = 'EXECUTED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decision_reason IS NOT NULL AND length(trim(decision_reason)) > 0 AND executed_at IS NOT NULL)
    ),
    CONSTRAINT chk_delegation_lifecycle_four_eyes
        CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT chk_delegation_lifecycle_time_order CHECK (
        (decided_at IS NULL OR decided_at >= proposed_at)
        AND (executed_at IS NULL OR (decided_at IS NOT NULL AND executed_at >= decided_at))
    )
);

CREATE INDEX idx_delegation_lifecycle_pending
    ON delegation_lifecycle_approvals (state, proposed_at);
CREATE INDEX idx_delegation_lifecycle_grant
    ON delegation_lifecycle_approvals (delegation_id, proposed_at DESC);

GRANT ALL ON delegation_lifecycle_approvals TO openbank;
