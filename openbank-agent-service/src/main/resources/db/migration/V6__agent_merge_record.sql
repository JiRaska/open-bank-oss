-- ADR-0031 phase 3, "recorded human merge": durable evidence of WHO merged an agent-authored
-- PR. agent.proposal.decided records proposal decisions; this table records the git merge itself,
-- ingested by a GitHub Actions workflow over POST /api/v1/agent-merge-records. The UNIQUE
-- constraint on pr_number is the dedupe guard for webhook redelivery (the repository inserts
-- ON CONFLICT DO NOTHING) and is itself the index serving pr_number lookups.
-- Rollback: DROP TABLE agent_merge_record; — rows are unaudited-event evidence (an ingest
-- emits an audit outbox row, but the record itself is the queryable source); export them first
-- if the merge evidence must be kept.
CREATE TABLE agent_merge_record (
    id          UUID PRIMARY KEY,
    agent_id    VARCHAR(128) NOT NULL,
    pr_number   BIGINT       NOT NULL UNIQUE,
    pr_url      TEXT         NOT NULL,
    head_branch VARCHAR(256) NOT NULL,
    merged_by   VARCHAR(128) NOT NULL,
    merged_at   TIMESTAMPTZ  NOT NULL,
    title       TEXT         NOT NULL,
    proposal_id UUID,                    -- links agent_proposal when the caller can establish it
    recorded_at TIMESTAMPTZ  NOT NULL,
    source      VARCHAR(64)  NOT NULL    -- e.g. 'github-actions'
);

-- Per-agent merge history, newest first (the /iaops/agents/<id> drill-down).
CREATE INDEX idx_agent_merge_record_agent_merged ON agent_merge_record (agent_id, merged_at DESC);
