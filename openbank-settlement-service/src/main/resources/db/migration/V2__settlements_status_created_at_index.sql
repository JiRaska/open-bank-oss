-- SPDX-License-Identifier: Apache-2.0
-- Supports the stranded-settlement gauges (issue #5705): every 30s the gauge asks, per
-- non-terminal status, `count(*)` and `min(created_at)`. Without an index those are two
-- sequential scans of `settlements` per status per tick — six statuses, 120 scans a minute,
-- growing with the table. A composite (status, created_at) serves both: the count from an
-- index-only scan, the oldest row from the first entry of the status prefix.
--
-- Rollback note: additive and non-locking in effect (a fresh index on an existing table).
-- Rollback = DROP INDEX IF EXISTS idx_settlements_status_created_at;
CREATE INDEX IF NOT EXISTS idx_settlements_status_created_at
    ON settlements (status, created_at);
