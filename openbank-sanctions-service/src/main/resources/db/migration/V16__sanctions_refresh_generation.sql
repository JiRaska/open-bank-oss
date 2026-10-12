-- SPDX-License-Identifier: Apache-2.0
-- A refresh generation fences independently committed import batches and publication.
-- The transaction holding a batch's row lock commits before a successor increments the
-- generation; any later operation from the old refresh then sees a stale token and fails.
-- A stranded active=true row defers background publication until the next refresh takes over.
-- Rollback: stop refreshes and publishers, then drop both columns only after all pods using
-- generation checks have been rolled back. Pending journal rows remain untouched.
ALTER TABLE sanctions_change_publication
    ADD COLUMN refresh_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN refresh_active BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE sanctions_change_publication
    ADD CONSTRAINT sanctions_refresh_generation_nonnegative CHECK (refresh_generation >= 0);
