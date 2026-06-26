-- ADR-0031 D7: runtime break-glass kill switch. One row per scope: an agent id, or the
-- sentinel '*' for the global halt that stops every agent. A row's presence with halted=true
-- means the scope is suspended; resume deletes the row (config baseline in agents.yaml is the
-- desired-state default, this table is the immediate override on top of it). Plain JDBC like
-- agent_proposal (this service can't run reactive Panache — see AgentProposalEntity doc).
CREATE TABLE agent_kill_switch (
    scope      VARCHAR(128) PRIMARY KEY,   -- agent id, or '*' for the global halt
    halted     BOOLEAN      NOT NULL DEFAULT TRUE,
    reason     TEXT         NOT NULL,
    set_by     VARCHAR(128) NOT NULL,      -- the operator (OIDC subject) who flipped it
    set_at     TIMESTAMPTZ  NOT NULL
);
