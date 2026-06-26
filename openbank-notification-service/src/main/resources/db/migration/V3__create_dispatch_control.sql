-- ADR-0034 Tier A break-glass control plane for the notification dispatch loop.
-- Append-only desired-state log: every replica reads the latest version_no per control_key
-- and converges. Rollback: DROP TABLE dispatch_resume_proposal; DROP TABLE dispatch_control_log;

CREATE TABLE dispatch_control_log (
    id BIGSERIAL PRIMARY KEY,
    control_key VARCHAR(128) NOT NULL,
    state VARCHAR(16) NOT NULL,
    version_no BIGINT NOT NULL,
    reason TEXT,
    actor VARCHAR(256),
    effective_from TIMESTAMPTZ NOT NULL,
    deferred_review_required BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dispatch_control_log_key_version
    ON dispatch_control_log(control_key, version_no DESC);

CREATE TABLE dispatch_resume_proposal (
    id BIGSERIAL PRIMARY KEY,
    proposal_id VARCHAR(64) NOT NULL UNIQUE,
    control_key VARCHAR(128) NOT NULL,
    reason TEXT,
    proposed_by VARCHAR(256) NOT NULL,
    proposed_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(16) NOT NULL,
    decided_by VARCHAR(256),
    decided_at TIMESTAMPTZ,
    decision_reason TEXT,
    executed_at TIMESTAMPTZ
);

CREATE INDEX idx_dispatch_resume_proposal_key_state
    ON dispatch_resume_proposal(control_key, state);
