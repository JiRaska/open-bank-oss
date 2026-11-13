-- SPDX-License-Identifier: Apache-2.0
-- Evidence from explicit AWAITING_SIGNATURE -> SIGNED transitions. Existing rows retain NULL.
-- Rollback: revert the new writer/reader and retain the column to preserve recorded evidence.
ALTER TABLE loan_application ADD COLUMN signature_recorded_at TIMESTAMPTZ;
