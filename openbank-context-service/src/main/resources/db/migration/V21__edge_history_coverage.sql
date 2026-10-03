-- Derived coverage metadata identifies when retained observations supersede a current baseline.
-- This timestamp is not a source fact and does not change current validity or provenance.
-- Drain/stop Context readers and consumers; restart only the matching coverage-aware binary.
-- Old SELECT-star readers and writers without coverage maintenance cannot overlap this transition.
-- Rollback: stop historical readers before dropping the indexes and column. Retaining this
-- metadata is harmless; observation tables remain governed evidence and must be retained.
ALTER TABLE context_edges ADD COLUMN retained_history_from timestamptz;

DO $$
DECLARE
    scoped_bank text;
    previous_bank text := current_setting('openbank.bank_scope', true);
BEGIN
    FOR scoped_bank IN SELECT DISTINCT bank_scope FROM context_edges LOOP
        PERFORM set_config('openbank.bank_scope', scoped_bank, true);
        UPDATE context_edges current_edge
        SET retained_history_from = history.first_observation
        FROM (
            SELECT bank_scope, projection_generation, namespace, from_key, to_key, relation_type, source_system,
                   MIN(valid_from) AS first_observation
            FROM context_graph_edge_revisions
            WHERE bank_scope = scoped_bank
            GROUP BY bank_scope, projection_generation, namespace, from_key, to_key, relation_type, source_system
        ) history
        WHERE current_edge.bank_scope = scoped_bank
          AND current_edge.bank_scope = history.bank_scope
          AND current_edge.projection_generation = history.projection_generation
          AND current_edge.namespace = history.namespace
          AND current_edge.from_key = history.from_key AND current_edge.to_key = history.to_key
          AND current_edge.relation_type = history.relation_type
          AND current_edge.source_system = history.source_system;
    END LOOP;
    PERFORM set_config('openbank.bank_scope', COALESCE(previous_bank, ''), true);
END;
$$;

CREATE INDEX idx_context_edge_legacy_baseline ON context_edges
    (bank_scope, projection_generation, namespace, from_key, valid_from DESC, edge_id DESC)
    WHERE retained_history_from IS NULL;
CREATE INDEX idx_context_edge_future_history ON context_edges
    (bank_scope, projection_generation, namespace, from_key, retained_history_from)
    WHERE retained_history_from IS NOT NULL;
CREATE INDEX idx_context_edge_coverage_identity ON context_edges
    (bank_scope, projection_generation, from_key, to_key, relation_type, source_system);
