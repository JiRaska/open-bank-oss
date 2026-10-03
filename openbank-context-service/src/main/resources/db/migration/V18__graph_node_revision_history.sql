-- Retain normalized graph facts, not source payloads or personal narratives.
-- Rollback before ingestion: DROP TABLE context_graph_node_revisions;
-- DROP TABLE context_graph_event_revisions;
-- After ingestion: stop readers/writers and retain under governed evidence retention.
CREATE TABLE context_graph_event_revisions (
    bank_scope varchar(80) NOT NULL,
    projection_generation bigint NOT NULL,
    evidence_ref varchar(300) NOT NULL,
    aggregate_ref varchar(300) NOT NULL,
    recorded_at timestamptz NOT NULL,
    content_hash varchar(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (bank_scope, projection_generation, evidence_ref)
);
CREATE TRIGGER context_graph_event_revisions_append_only
    BEFORE UPDATE OR DELETE ON context_graph_event_revisions
    FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();
ALTER TABLE context_graph_event_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_graph_event_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY context_graph_event_revisions_bank_scope ON context_graph_event_revisions
    USING (bank_scope = current_setting('openbank.bank_scope', true))
    WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));
GRANT SELECT, INSERT ON context_graph_event_revisions TO openbank;

CREATE TABLE context_graph_node_revisions (
    node_revision_id uuid PRIMARY KEY,
    bank_scope varchar(80) NOT NULL,
    projection_generation bigint NOT NULL,
    namespace varchar(40) NOT NULL CHECK (namespace = 'COMPLAINT'),
    node_key varchar(300) NOT NULL,
    node_type varchar(80) NOT NULL,
    source_system varchar(80) NOT NULL,
    source_ref varchar(300) NOT NULL,
    aggregate_ref varchar(300) NOT NULL,
    evidence_ref varchar(300) NOT NULL,
    display_label varchar(500) NOT NULL,
    classification varchar(40) NOT NULL CHECK (classification = 'RESTRICTED'),
    valid_from timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    source_version bigint NOT NULL CHECK (source_version >= 0),
    authoritative boolean NOT NULL,
    content_hash varchar(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (bank_scope, projection_generation, evidence_ref, node_key)
);

CREATE INDEX idx_context_graph_node_owner_history ON context_graph_node_revisions
    (bank_scope, projection_generation, node_key, source_version DESC, valid_from)
    WHERE authoritative;
CREATE UNIQUE INDEX idx_context_graph_node_owner_revision ON context_graph_node_revisions
    (bank_scope, projection_generation, node_key, source_system, source_version)
    WHERE authoritative;
CREATE INDEX idx_context_graph_node_reference_history ON context_graph_node_revisions
    (bank_scope, projection_generation, node_key, valid_from DESC, source_version DESC)
    WHERE NOT authoritative;

CREATE TRIGGER context_graph_node_revisions_append_only
    BEFORE UPDATE OR DELETE ON context_graph_node_revisions
    FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();
ALTER TABLE context_graph_node_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_graph_node_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY context_graph_node_revisions_bank_scope ON context_graph_node_revisions
    USING (bank_scope = current_setting('openbank.bank_scope', true))
    WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));
GRANT SELECT, INSERT ON context_graph_node_revisions TO openbank;
