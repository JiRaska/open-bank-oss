-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 D3 hardening: an optimistic row version on every reference record, so a decision that
-- read a row before another decision committed fails instead of overwriting it (the decision path
-- also locks every version of the key FOR UPDATE), and an approval note that records what an
-- approval changed — for a counterparty, the limits before and after.
--
-- Rollback (additive only): ALTER TABLE reference_records DROP COLUMN row_version, DROP COLUMN approval_note;

ALTER TABLE reference_records
    ADD COLUMN row_version   BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN approval_note TEXT;
