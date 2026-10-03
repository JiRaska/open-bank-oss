-- SPDX-License-Identifier: Apache-2.0
-- Supports the bounded Customer 360 party application read. The existing party-only
-- index cannot satisfy ORDER BY created_at DESC, id DESC and scans/sorts a heavy
-- party's full history before LIMIT. A composite index stops after the requested rows.
-- For a large live table, prebuild this exact index CONCURRENTLY out of band before
-- rolling the release; IF NOT EXISTS then makes the Flyway step quick. Flyway's
-- transactional startup migration must not build a large index under write load.
-- Rollback after rolling back the bounded read: DROP INDEX IF EXISTS idx_loan_application_party_recent;
CREATE INDEX IF NOT EXISTS idx_loan_application_party_recent
    ON loan_application (party_id, created_at DESC, id DESC);
