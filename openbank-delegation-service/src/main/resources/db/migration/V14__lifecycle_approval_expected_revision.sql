-- Bind lifecycle proposals to the exact grant revision the maker inspected. Existing V13 rows
-- deliberately remain NULL: they are durable evidence but must never be executed without an
-- authoritative revision snapshot. New writers always populate the column.
--
-- Rollback: keep lifecycle-approval mutations disabled, then roll back application images. The
-- nullable additive column is harmless to old readers. Do not drop it in place; use a new forward
-- migration only after all evidence retention obligations have elapsed.
ALTER TABLE delegation_lifecycle_approvals
    ADD COLUMN expected_lifecycle_revision BIGINT;

ALTER TABLE delegation_lifecycle_approvals
    ADD CONSTRAINT chk_delegation_lifecycle_expected_revision
        CHECK (expected_lifecycle_revision IS NULL OR expected_lifecycle_revision >= 0);

CREATE INDEX idx_delegation_lifecycle_pending_revision
    ON delegation_lifecycle_approvals (state, delegation_id, expected_lifecycle_revision)
    WHERE state = 'PROPOSED';
