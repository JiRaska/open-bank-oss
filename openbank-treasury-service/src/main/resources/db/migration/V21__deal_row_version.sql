-- SPDX-License-Identifier: Apache-2.0
-- Optimistic lock on the deal row (ADR-0315 lifecycle, ADR-0344 D2): every whole-row save of a deal
-- (edit, submit, approve, confirm, settle, mature, reverse, cancel, ...) takes the row FOR UPDATE and
-- compares row_version with the copy the command was computed from, answering 409 DEAL_CHANGED when
-- the row moved on; the JPA @Version column increments it on every write. Without it, a draft edit
-- racing a submission, or two edits, silently lost one of the two changes (last writer won).
-- Existing rows start at 0, which is what every copy loaded after this migration carries.
-- Rollback: ALTER TABLE deals DROP COLUMN row_version;
ALTER TABLE deals ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
