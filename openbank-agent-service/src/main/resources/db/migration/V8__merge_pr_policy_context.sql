-- Preserve existing actor/proof rows. Immutable pre-merge policy context is a separate PR proof.
-- Rollback: stop policy-PR ingestion; retain/export policy rows and their outbox evidence.
-- Deploy a previous reader only after excluding these rows from that reader's path. Do not
-- drop columns or restore V7's constraint while policy rows exist: that would discard evidence.
ALTER TABLE agent_merge_record
    ADD COLUMN policy_commit_sha VARCHAR(40),
    ADD COLUMN policy_blob_sha VARCHAR(40),
    DROP CONSTRAINT merge_app_evidence;
ALTER TABLE agent_merge_record
    ADD CONSTRAINT merge_app_evidence CHECK (
        (merged_by_type = 'HUMAN' AND approved_by IS NULL AND policy_commit_sha IS NULL AND policy_blob_sha IS NULL) OR
        (merged_by_type = 'GITHUB_APP' AND merged_by = 'openbank-gitops-bot[bot]'
         AND merged_by_id IS NOT NULL AND merged_by_id > 0
         AND head_sha IS NOT NULL AND head_sha ~ '^[0-9a-f]{40}$'
         AND merge_commit_sha IS NOT NULL AND merge_commit_sha ~ '^[0-9a-f]{40}$'
         AND approved_by IS NOT NULL AND approved_by ~ '^[A-Za-z0-9][A-Za-z0-9-]{0,38}$'
         AND approved_by_id IS NOT NULL AND approved_by_id > 0
         AND approved_at IS NOT NULL AND approved_at <= merged_at
         AND authorization_kind IS NOT NULL AND authorization_scope IS NOT NULL
         AND (
           (policy_commit_sha IS NULL AND policy_blob_sha IS NULL AND authorization_kind = 'OWNER_WORKFLOW_EXACT_HEAD' AND authorization_scope = 'HEAD'
            AND authorization_status_id IS NOT NULL AND authorization_status_id > 0
            AND authorization_workflow_run_id IS NOT NULL AND authorization_workflow_run_id > 0
            AND authorization_evidence_url IS NOT NULL
            AND authorization_evidence_url = 'https://github.com/JiRaska/open-bank-oss/actions/runs/' || authorization_workflow_run_id
            AND pull_request_node_id IS NULL AND merge_event_node_id IS NULL
            AND merge_event_at IS NULL AND authorization_observed_at IS NULL)
           OR
           (policy_commit_sha IS NULL AND policy_blob_sha IS NULL AND authorization_kind = 'LEGACY_PR_AUTO_MERGE' AND authorization_scope = 'PR'
            AND merged_at <= '2026-10-23T19:42:17Z'::timestamptz
            AND authorization_status_id IS NULL AND authorization_workflow_run_id IS NULL
            AND authorization_evidence_url IS NOT NULL
            AND authorization_evidence_url = 'https://github.com/JiRaska/open-bank-oss/pull/' || pr_number
            AND pull_request_node_id IS NOT NULL AND length(trim(pull_request_node_id)) > 0
            AND merge_event_node_id IS NOT NULL AND length(trim(merge_event_node_id)) > 0
            AND merge_event_at IS NOT NULL AND approved_at <= merge_event_at
            AND authorization_observed_at IS NOT NULL AND authorization_observed_at >= merged_at
            AND authorization_observed_at >= merge_event_at)
           OR
           (policy_commit_sha IS NOT NULL AND policy_commit_sha ~ '^[0-9a-f]{40}$'
            AND policy_blob_sha IS NOT NULL AND policy_blob_sha ~ '^[0-9a-f]{40}$'
            AND head_branch NOT LIKE 'codex/%'
            AND authorization_kind = 'POLICY_PR_AUTO_MERGE' AND authorization_scope = 'PR'
            AND authorization_status_id IS NULL AND authorization_workflow_run_id IS NULL
            AND authorization_evidence_url IS NOT NULL
            AND authorization_evidence_url = 'https://github.com/JiRaska/open-bank-oss/pull/' || pr_number
            AND pull_request_node_id IS NOT NULL AND length(trim(pull_request_node_id)) > 0
            AND merge_event_node_id IS NOT NULL AND length(trim(merge_event_node_id)) > 0
            AND merge_event_at IS NOT NULL AND approved_at <= merge_event_at
            AND authorization_observed_at IS NOT NULL AND authorization_observed_at >= merged_at
            AND authorization_observed_at >= merge_event_at
            AND abs(extract(epoch FROM (merge_event_at - merged_at))) <= 60)
         ))
    );
