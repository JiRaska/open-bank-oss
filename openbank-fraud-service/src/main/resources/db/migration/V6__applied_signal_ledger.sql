-- Issue #5789: both fraud aggregates deduplicated redelivery with a LAST-WRITER marker
-- (payee_history since V3, velocity_aggregates since V5), which only catches a replay that is
-- consecutive for that row. The reachable failure is not exotic: the source topic
-- openbank.transactions.transaction.initiated is keyed by the transaction aggregateId, so two
-- signals for the same account are two different keys and any at-least-once replay of an
-- uncommitted offset window re-delivers them in the order A, B. After B the marker holds B, so the
-- replayed A is DISTINCT FROM it and is applied a second time — and so is B after it. The marker
-- therefore protects a replay window of exactly one record.
--
-- The fix is the applied-event set the marker was standing in for: each aggregate row remembers the
-- ids it has actually applied, and the upsert's WHERE tests membership of that set rather than
-- equality with the newest entry.
--
-- Bound (the design decision, stated rather than hidden): the set is bounded by a COUNT window, not
-- by a duration — the most recent N ids applied to that row, N configurable via
-- openbank.fraud.applied-signal-window (default 100). Chosen over a time-bounded ledger table
-- because it needs no retention sweep to stay bounded (a per-row array is trimmed on every write,
-- so it cannot grow without a scheduler running), and it stays inside the one statement that
-- already applies the increment, so the memory of "applied" and the effect of applying commit
-- together with no window in between. The cost is that the guarantee is expressed in events rather
-- than in time: a replay that arrives after N further signals to the SAME row is once again
-- re-applied. For velocity_aggregates a row is one (account, window, currency, bucket), so N=100 is
-- 100 transactions inside that bucket; for payee_history it is 100 payments to the same payee.
--
-- Backfill: each existing row already carries exactly one applied id in last_transaction_id, so the
-- set is seeded from it and the protection that exists today is not lost at cutover. Rows whose
-- marker is NULL (no signal recorded yet, or a signal with no aggregateId) seed to an empty set.
-- last_transaction_id is deliberately KEPT and is still part of the guard: the upsert tests membership
-- of applied_transaction_ids UNIONED with last_transaction_id. That is what covers the rolling-deploy
-- window this backfill cannot — a pod still running the V5/V3 code writes the marker and leaves the
-- new column untouched, so a row it applies after this migration runs would otherwise remember nothing.
-- The union makes the new guard strictly stronger than the old one in every state, never weaker.
--
-- Rollback:
--   DROP FUNCTION IF EXISTS fraud_append_applied(UUID[], UUID, INT);
--   ALTER TABLE velocity_aggregates DROP COLUMN applied_transaction_ids;
--   ALTER TABLE payee_history       DROP COLUMN applied_transaction_ids;
-- (last_transaction_id is untouched by this migration, so a rollback returns both tables to the
-- V3/V5 last-writer guard rather than to no guard at all.)

ALTER TABLE velocity_aggregates
    ADD COLUMN applied_transaction_ids UUID[] NOT NULL DEFAULT '{}';

ALTER TABLE payee_history
    ADD COLUMN applied_transaction_ids UUID[] NOT NULL DEFAULT '{}';

UPDATE velocity_aggregates
   SET applied_transaction_ids = array_remove(ARRAY[last_transaction_id], NULL)
 WHERE last_transaction_id IS NOT NULL;

UPDATE payee_history
   SET applied_transaction_ids = array_remove(ARRAY[last_transaction_id], NULL)
 WHERE last_transaction_id IS NOT NULL;

-- Append new_id and keep only the most recent `cap` entries. IMMUTABLE so it can be used inside the
-- upsert's SET without blocking the planner. A cap <= 0 is clamped to 1 so a misconfiguration
-- degrades to the old last-writer behaviour rather than to an empty set (which would be NO guard).
CREATE OR REPLACE FUNCTION fraud_append_applied(ids UUID[], new_id UUID, cap INT)
RETURNS UUID[]
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
               WHEN cardinality(a) > GREATEST(cap, 1)
                   THEN a[cardinality(a) - GREATEST(cap, 1) + 1 : cardinality(a)]
               ELSE a
           END
      FROM (
          -- A signal with no aggregateId carries no identity to deduplicate by, so it is applied
          -- unconditionally and must NOT be remembered: appending NULL would grow the set with
          -- entries that can never match anything.
          SELECT CASE
                     WHEN new_id IS NULL THEN COALESCE(ids, '{}'::uuid[])
                     ELSE array_append(COALESCE(ids, '{}'::uuid[]), new_id)
                 END AS a
      ) s
$$;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
