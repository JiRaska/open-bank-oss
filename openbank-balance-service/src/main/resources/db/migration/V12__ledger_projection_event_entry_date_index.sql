-- ADR-0178 Phase 2 (#1745): value-date-aware effective-booked balance.
--
-- The spendable figure is DERIVED, not materialized — there is deliberately no `effective_booked`
-- column here. `Balance.effectiveAvailable()` subtracts the not-yet-effective credit tail read per
-- request from this audit table, so the figure becomes correct on its own the moment the accounting
-- day passes a credit's value date, with no promotion job having to have run. A materialized column
-- would make a missed daily roll a wrong money figure; derived makes it a delayed notification.
--
-- That puts two new predicates on the hot read path, and `ledger_projection_event` had no index on
-- `entry_date` at all (V6 created only `idx_ledger_projection_event_tx`):
--
--   1. per-balance tail  — WHERE account_id = ? AND currency = ? AND entry_date > ? AND delta > 0
--      Runs on every balance read AND on every placeHold cover decision.
--   2. daily roll        — WHERE entry_date = ? AND delta > 0
--      Runs once a day (ValueDateRollScheduler).
--
-- Both are restricted to strictly-positive deltas (credits), which is a small minority of rows, so
-- both indexes are PARTIAL on `delta > 0` — this keeps them a fraction of the table size and lets
-- the planner use them without re-checking the sign. `delta > 0` is IMMUTABLE, so it is a legal
-- partial-index predicate.
--
-- Rollback note:
--   DROP INDEX IF EXISTS idx_lpe_credit_tail;
--   DROP INDEX IF EXISTS idx_lpe_credit_maturing;
-- (Fully reversible: index-only migration. It creates no column, changes no row and holds no data —
--  dropping both restores the pre-migration schema exactly. Queries stay CORRECT without them and
--  only get slower, so a rollback is safe at any time, including under load.)
--
-- NB: CREATE INDEX (not CONCURRENTLY) — Flyway runs migrations inside a transaction and
-- CONCURRENTLY cannot run in one. This table is the projection dedup audit, written once per
-- projected journal line; the brief write lock at migration time is acceptable at its size. If it
-- ever grows past that, build the index CONCURRENTLY out-of-band first and make this a no-op via
-- IF NOT EXISTS (which is already how it is written).

-- 1. Per-balance not-yet-effective credit tail. account_id + currency lead (equality), entry_date
--    last (range) — the standard composite ordering for this predicate shape.
CREATE INDEX IF NOT EXISTS idx_lpe_credit_tail
    ON ledger_projection_event (account_id, currency, entry_date)
    WHERE delta > 0;

-- 2. Credits maturing on a given accounting day, for the daily roll's maturity announcement.
CREATE INDEX IF NOT EXISTS idx_lpe_credit_maturing
    ON ledger_projection_event (entry_date)
    WHERE delta > 0;
