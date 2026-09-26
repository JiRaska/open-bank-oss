-- Retain normalized relationship observations and their complete source provenance.
-- No historical facts are inferred from current projection rows.
-- Rollback before ingestion: DROP TABLE context_graph_edge_revisions;
-- DROP TABLE context_graph_edge_events;
-- After ingestion: stop readers/writers and retain these tables under evidence retention.
CREATE TABLE context_graph_edge_events (
    bank_scope varchar(80) NOT NULL,
    projection_generation bigint NOT NULL,
    evidence_ref varchar(300) NOT NULL,
    aggregate_ref varchar(300) NOT NULL,
    recorded_at timestamptz NOT NULL,
    content_hash varchar(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (bank_scope, projection_generation, evidence_ref)
);
CREATE TRIGGER context_graph_edge_events_append_only
    BEFORE UPDATE OR DELETE ON context_graph_edge_events
    FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();
ALTER TABLE context_graph_edge_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_graph_edge_events FORCE ROW LEVEL SECURITY;
CREATE POLICY context_graph_edge_events_bank_scope ON context_graph_edge_events
    USING (bank_scope = current_setting('openbank.bank_scope', true))
    WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));
GRANT SELECT, INSERT ON context_graph_edge_events TO openbank;

CREATE TABLE context_graph_edge_revisions (
    edge_revision_id uuid PRIMARY KEY,
    edge_id uuid NOT NULL,
    bank_scope varchar(80) NOT NULL,
    projection_generation bigint NOT NULL,
    namespace varchar(40) NOT NULL CHECK (namespace = 'COMPLAINT'),
    from_key varchar(300) NOT NULL,
    to_key varchar(300) NOT NULL,
    relation_type varchar(80) NOT NULL,
    source_system varchar(80) NOT NULL,
    aggregate_ref varchar(300) NOT NULL,
    evidence_ref varchar(300) NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz CHECK (valid_to IS NULL),
    recorded_at timestamptz NOT NULL,
    source_version bigint NOT NULL CHECK (source_version >= 0),
    content_hash varchar(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (bank_scope, projection_generation, evidence_ref, edge_id)
);
CREATE INDEX idx_context_graph_edge_from_history ON context_graph_edge_revisions
    (bank_scope, projection_generation, namespace, from_key, valid_from, edge_id);
CREATE INDEX idx_context_graph_edge_identity_history ON context_graph_edge_revisions
    (bank_scope, projection_generation, edge_id, valid_from DESC, source_version DESC, evidence_ref);
CREATE INDEX idx_context_graph_edge_baseline_history ON context_graph_edge_revisions
    (bank_scope, projection_generation, from_key, to_key, relation_type, source_system, valid_from);
CREATE TRIGGER context_graph_edge_revisions_append_only
    BEFORE UPDATE OR DELETE ON context_graph_edge_revisions
    FOR EACH ROW EXECUTE FUNCTION reject_context_audit_mutation();
ALTER TABLE context_graph_edge_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_graph_edge_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY context_graph_edge_revisions_bank_scope ON context_graph_edge_revisions
    USING (bank_scope = current_setting('openbank.bank_scope', true))
    WITH CHECK (bank_scope = current_setting('openbank.bank_scope', true));
GRANT SELECT, INSERT ON context_graph_edge_revisions TO openbank;
