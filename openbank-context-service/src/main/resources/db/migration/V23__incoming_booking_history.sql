-- SPDX-License-Identifier: Apache-2.0
-- Complaint transaction references join payment identity through authoritative incoming booking evidence.
-- Rollback: DROP INDEX idx_context_graph_edge_to_history; no projected facts are changed.
CREATE INDEX idx_context_graph_edge_to_history ON context_graph_edge_revisions
    (bank_scope, projection_generation, namespace, to_key, valid_from, edge_id);
