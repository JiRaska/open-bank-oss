-- SPDX-License-Identifier: Apache-2.0
-- Bind a compliance clearance to an explicit subject and give it a bounded lifetime.
-- Existing rows keep NULL subject_id and therefore cannot acquire a new WHITELISTED review;
-- they must be screened again before an analyst can clear the new check.
-- Rollback: stop clearance lookups, roll back all pods to code that does not read these columns,
-- then drop idx_sanctions_subject_status, clearance_expires_at and subject_id in one migration.
ALTER TABLE sanctions_checks
    ADD COLUMN subject_id VARCHAR(255),
    ADD COLUMN clearance_expires_at TIMESTAMPTZ;

CREATE INDEX idx_sanctions_subject_status
    ON sanctions_checks(subject_id, status)
    WHERE subject_id IS NOT NULL;
