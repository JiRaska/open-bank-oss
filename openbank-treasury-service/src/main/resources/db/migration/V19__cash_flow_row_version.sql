-- SPDX-License-Identifier: Apache-2.0
-- Optimistic lock on the stored cash-flow row (ADR-0344 D4-D6): every write of a flow increments
-- row_version, so a writer holding a stale copy (override approval, fixing run, payment proposal)
-- fails at flush instead of overwriting. The payment link and the fixing provenance are moreover
-- written only by their own guarded UPDATEs, never by a whole-row save.
-- Rollback: ALTER TABLE deal_cash_flows DROP COLUMN row_version;
ALTER TABLE deal_cash_flows ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
