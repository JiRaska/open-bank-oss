-- ADR-0031 D4: the agent's HITL approval queue. A control-plane agent proposes a
-- reviewable action; a human approves/rejects before anything happens. The agent is
-- otherwise stateless — this single table is its only persistence.
CREATE TABLE agent_proposal (
    id               UUID PRIMARY KEY,
    title            TEXT        NOT NULL,
    rationale        TEXT        NOT NULL,
    suggested_action TEXT        NOT NULL,
    proposed_by      VARCHAR(128) NOT NULL,
    proposed_at      TIMESTAMPTZ NOT NULL,
    state            VARCHAR(16) NOT NULL DEFAULT 'PROPOSED',
    decided_by       VARCHAR(128),
    decided_at       TIMESTAMPTZ,
    decision_reason  TEXT,
    model_id         VARCHAR(128),
    correlation_id   VARCHAR(128)
);

-- Approval queue read pattern: list PROPOSED first, newest first.
CREATE INDEX idx_agent_proposal_state ON agent_proposal (state, proposed_at DESC);
