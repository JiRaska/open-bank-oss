-- Existing human records remain HUMAN; no historical actor or proof is rewritten.
-- Rollback: stop App ingestion, export App evidence and outbox rows, deploy the previous
-- reader only after removing App records from its read path. Drop the added columns only
-- after evidence retention is satisfied; destructive rollback loses authorization evidence.
ALTER TABLE agent_merge_record
    ADD COLUMN merged_by_type VARCHAR(16) NOT NULL DEFAULT 'HUMAN',
    ADD COLUMN merged_by_id BIGINT,
    ADD COLUMN head_sha VARCHAR(40),
    ADD COLUMN merge_commit_sha VARCHAR(40),
    ADD COLUMN approved_by VARCHAR(128),
    ADD COLUMN approved_by_id BIGINT,
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN authorization_evidence_url TEXT,
    ADD COLUMN authorization_status_id BIGINT,
    ADD COLUMN authorization_workflow_run_id BIGINT,
    ADD COLUMN authorization_kind VARCHAR(32),
    ADD COLUMN authorization_scope VARCHAR(4),
    ADD COLUMN pull_request_node_id TEXT,
    ADD COLUMN merge_event_node_id TEXT,
    ADD COLUMN merge_event_at TIMESTAMPTZ,
    ADD COLUMN authorization_observed_at TIMESTAMPTZ,
    ADD CONSTRAINT merge_actor_type CHECK (merged_by_type IN ('HUMAN', 'GITHUB_APP')),
    ADD CONSTRAINT merge_app_evidence CHECK (
        (merged_by_type = 'HUMAN' AND approved_by IS NULL) OR
        (merged_by_type = 'GITHUB_APP' AND merged_by = 'openbank-gitops-bot[bot]'
         AND merged_by_id IS NOT NULL AND merged_by_id > 0
         AND head_sha IS NOT NULL AND head_sha ~ '^[0-9a-f]{40}$'
         AND merge_commit_sha IS NOT NULL AND merge_commit_sha ~ '^[0-9a-f]{40}$'
         AND approved_by IS NOT NULL AND approved_by ~ '^[A-Za-z0-9][A-Za-z0-9-]{0,38}$'
         AND approved_by_id IS NOT NULL AND approved_by_id > 0
         AND approved_at IS NOT NULL AND approved_at <= merged_at
         AND authorization_kind IS NOT NULL AND authorization_scope IS NOT NULL
         AND (
           (authorization_kind = 'OWNER_WORKFLOW_EXACT_HEAD' AND authorization_scope = 'HEAD'
            AND authorization_status_id IS NOT NULL AND authorization_status_id > 0
            AND authorization_workflow_run_id IS NOT NULL AND authorization_workflow_run_id > 0
            AND authorization_evidence_url IS NOT NULL
            AND authorization_evidence_url = 'https://github.com/JiRaska/open-bank-oss/actions/runs/' || authorization_workflow_run_id
            AND pull_request_node_id IS NULL AND merge_event_node_id IS NULL
            AND merge_event_at IS NULL AND authorization_observed_at IS NULL)
           OR
           (authorization_kind = 'LEGACY_PR_AUTO_MERGE' AND authorization_scope = 'PR'
            AND merged_at <= '2026-10-23T19:42:17Z'::timestamptz
            AND authorization_status_id IS NULL AND authorization_workflow_run_id IS NULL
            AND authorization_evidence_url IS NOT NULL
            AND authorization_evidence_url = 'https://github.com/JiRaska/open-bank-oss/pull/' || pr_number
            AND pull_request_node_id IS NOT NULL AND length(trim(pull_request_node_id)) > 0
            AND merge_event_node_id IS NOT NULL AND length(trim(merge_event_node_id)) > 0
            AND merge_event_at IS NOT NULL AND approved_at <= merge_event_at
            AND authorization_observed_at IS NOT NULL AND authorization_observed_at >= merged_at
            AND authorization_observed_at >= merge_event_at)
         ))
    );
