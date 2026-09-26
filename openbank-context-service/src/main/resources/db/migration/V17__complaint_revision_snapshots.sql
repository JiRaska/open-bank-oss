-- Immutable source-versioned complaint snapshots. No historical backfill is possible from
-- the current-state graph: older complaint links and labels were overwritten there.
-- Rollback before writes: DROP TABLE context_complaint_revisions;
-- After writes, stop the consumer/reader and retain evidence for its approved retention period.
CREATE TABLE context_complaint_revisions (
    bank_scope varchar(80) NOT NULL,
    projection_generation bigint NOT NULL,
    complaint_id uuid NOT NULL,
    source_version bigint NOT NULL CHECK (source_version > 0),
    reference varchar(200) NOT NULL,
    event_key varchar(300) NOT NULL,
    event_type varchar(80) NOT NULL,
    status varchar(80) NOT NULL,
    account_id uuid,
    transaction_id uuid,
    dispute_id uuid,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    content_hash varchar(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (bank_scope, projection_generation, complaint_id, source_version),
    UNIQUE (bank_scope, projection_generation, event_key)
);

CREATE INDEX idx_context_complaint_revisions_snapshot
    ON context_complaint_revisions (bank_scope, projection_generation, reference, source_version DESC, occurred_at);

CREATE TRIGGER context_complaint_revisions_append_only
    BEFORE UPDATE OR DELETE ON context_complaint_revisions
    FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();

ALTER TABLE context_complaint_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_complaint_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY context_complaint_revisions_bank_scope ON context_complaint_revisions
    USING (bank_scope = current_setting('openbank.bank_scope', true))
    WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));

GRANT ALL ON context_complaint_revisions TO openbank;
