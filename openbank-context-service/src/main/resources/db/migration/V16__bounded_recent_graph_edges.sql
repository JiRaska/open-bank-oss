-- Rollback out of band, one statement at a time outside a transaction:
-- CREATE INDEX CONCURRENTLY idx_context_edges_from ON context_edges(bank_scope, projection_generation, namespace, from_key);
-- CREATE INDEX CONCURRENTLY idx_context_edges_to ON context_edges(bank_scope, projection_generation, namespace, to_key);
-- DROP INDEX CONCURRENTLY idx_context_edges_from_recent;
-- DROP INDEX CONCURRENTLY idx_context_edges_to_recent;
-- These indexes support a separately bounded scan in each direction before the result is merged.
-- The Context projection has its own database; build on a replica/staging volume before rollout.
CREATE INDEX idx_context_edges_from_recent
  ON context_edges (bank_scope, projection_generation, namespace, from_key, recorded_at DESC, edge_id);
CREATE INDEX idx_context_edges_to_recent
  ON context_edges (bank_scope, projection_generation, namespace, to_key, recorded_at DESC, edge_id);
-- The new indexes retain the old lookup prefixes. Keeping both pairs would double write work.
DROP INDEX idx_context_edges_from;
DROP INDEX idx_context_edges_to;
