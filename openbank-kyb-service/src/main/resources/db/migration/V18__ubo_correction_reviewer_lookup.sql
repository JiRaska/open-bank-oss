-- SPDX-License-Identifier: Apache-2.0
-- ADR-0305: reviewer decisions require an earlier audited candidate read.
-- Bound that existence lookup as the append-only audit table grows.
-- Rollback: DROP INDEX idx_kyb_ubo_correction_reads_reviewer; no evidence is removed.
CREATE INDEX idx_kyb_ubo_correction_reads_reviewer
    ON kyb_ubo_correction_reads (correction_id, principal_id);
