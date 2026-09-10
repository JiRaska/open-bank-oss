-- #9048: POST /refresh-all moves off the request path (202 + the existing 60s scheduler does the
-- work). This flag is the hand-off: the endpoint sets it on every enabled list, the scheduler's
-- due-check treats a set flag as due, and markUpdated clears it when that list's refresh commits.
--
-- Rollback: ALTER TABLE sanctions_lists DROP COLUMN IF EXISTS refresh_requested_at;
ALTER TABLE sanctions_lists ADD COLUMN IF NOT EXISTS refresh_requested_at TIMESTAMPTZ;
