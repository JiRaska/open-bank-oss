-- Legacy ledger rows have unknown generation; do not invent generation 1.
-- Stop and drain all five consumers before migration; restart only generation-aware writers.
-- Mixed old/new writers and old-binary rollback are unsupported after changing conflict targets.
-- Stop ingestion before migration. Rollback after replay: retain this schema and stop consumers;
-- restoring global uniqueness would collapse or lose evidence from multiple generations.
ALTER TABLE context_projection_events ADD COLUMN projection_generation bigint;
ALTER TABLE context_projection_events DROP CONSTRAINT context_projection_events_pkey;
ALTER TABLE context_projection_events ADD CONSTRAINT context_projection_events_generation_key
    UNIQUE (bank_scope, projection_generation, event_key);
CREATE UNIQUE INDEX idx_context_projection_legacy_event
    ON context_projection_events (bank_scope, event_key) WHERE projection_generation IS NULL;
DROP INDEX idx_context_incident_revision_digest;
CREATE UNIQUE INDEX idx_context_incident_revision_digest
    ON context_projection_events (bank_scope, projection_generation, aggregate_ref, source_version)
    WHERE source_system = 'security-scanner' AND content_digest IS NOT NULL;
CREATE UNIQUE INDEX idx_context_incident_legacy_revision_digest
    ON context_projection_events (bank_scope, aggregate_ref, source_version)
    WHERE projection_generation IS NULL AND source_system = 'security-scanner' AND content_digest IS NOT NULL;
-- Preserve existing edge identities while making their uniqueness explicitly scope-local.
ALTER TABLE context_edges DROP CONSTRAINT context_edges_pkey;
ALTER TABLE context_edges ADD PRIMARY KEY (bank_scope, projection_generation, edge_id);
