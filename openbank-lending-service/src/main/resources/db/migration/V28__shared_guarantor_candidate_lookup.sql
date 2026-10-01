-- SPDX-License-Identifier: Apache-2.0
-- Bounds the authorized candidate-loan lookup by loan and shared guarantor.
-- Rollback: DROP INDEX CONCURRENTLY IF EXISTS idx_lending_graph_guarantee_shared_candidate;
CREATE INDEX idx_lending_graph_guarantee_shared_candidate
    ON lending_graph_guarantee(loan_id, guarantor_party_id, valid_from DESC)
    WHERE status = 'APPROVED';
