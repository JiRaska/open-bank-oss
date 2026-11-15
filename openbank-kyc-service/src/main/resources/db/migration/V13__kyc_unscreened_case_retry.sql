-- SPDX-License-Identifier: Apache-2.0
-- Retry of the customer screen for a KYC case opened while sanctions-service was unavailable
-- (issue #15723).
--
-- A case whose screen could not be obtained keeps its SANCTIONS_SCREENING check PENDING, and the
-- party event that opened it is acknowledged. The screen needs the party's legal name, which the
-- case does not store and which only that event carried, so nothing could ever ask again.
--
--   pending_screening_name  the name to screen, kept ONLY while a retry still needs it: written
--                           when a screen comes back unavailable, cleared when the check is
--                           settled, when the case closes, and on GDPR erasure of the party.
--                           NULL on every existing row and on every case that was screened.
--   screening_attempted_at  time of the last attempt; the sweep takes the least recently
--                           attempted cases first, so one case that keeps failing cannot hold
--                           the head of the queue.
--
-- Neither column is part of the case's API, event or stored-check payload.
--
-- Rollback: DROP INDEX idx_kyc_cases_screening_retry; ALTER TABLE kyc_cases
-- DROP COLUMN pending_screening_name, DROP COLUMN screening_attempted_at. Both columns are
-- nullable and unread by the previous release, which keeps working against the migrated schema.

ALTER TABLE kyc_cases
    ADD COLUMN pending_screening_name TEXT,
    ADD COLUMN screening_attempted_at TIMESTAMPTZ;

-- Partial: only the handful of cases awaiting a retry are indexed.
CREATE INDEX idx_kyc_cases_screening_retry
    ON kyc_cases (screening_attempted_at) WHERE pending_screening_name IS NOT NULL;
