-- ADR-0327 D2 — the ONE additive migration a service adds when it adopts
-- AbstractPanacheOutboxRepository (Phase 2/3 of #11652). This file is a TEMPLATE: it lives
-- under db/ (not db/migration/) and carries no V<n>__ prefix, so no Flyway ever picks it up
-- from this jar. Copy it into <service>/src/main/resources/db/migration/V<n>__outbox_v2.sql,
-- replace <t> with the service's table prefix (ledger_outbox -> "ledger"), and keep the
-- statements in this order.
--
-- Rollback: before the migration is applied, DROP INDEX IF EXISTS ix_<t>_outbox_claim,
-- ix_<t>_outbox_inflight_aggregate and ALTER TABLE <t>_outbox DROP COLUMN next_attempt_at.
-- Never edit an applied Flyway migration (checksum mismatch at boot).
--
-- Plain CREATE INDEX, never CONCURRENTLY, from Flyway: Quarkus runs every migration inside one
-- transaction and CONCURRENTLY cannot run in one; account-service's V10 records the measured
-- consequence of trying (the build races the app's own pool and is cancelled by lock_timeout).
-- Plain is safe here because both indexes are PARTIAL — they cover only rows in flight
-- (PENDING/FAILED/DISPATCHING), so the build reads the table once and writes a small index.
--
-- RUNBOOK RULE (not a gate): on a table already past ~1 M rows in production, an operator
-- pre-creates BOTH indexes by the SAME NAME with CREATE INDEX CONCURRENTLY, out of band, before
-- the deploy:
--     CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_<t>_outbox_claim ON <t>_outbox (created_at, id)
--         WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
--     CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_<t>_outbox_inflight_aggregate
--         ON <t>_outbox (aggregate_id, created_at, id)
--         WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
-- IF NOT EXISTS then makes the migration's CREATE INDEX a no-op, so the deploy never holds the
-- table lock. Check pg_index.indisvalid = true for both before deploying — a CONCURRENTLY build
-- that was interrupted leaves an INVALID index, and IF NOT EXISTS will happily skip past it.
--
-- The OLD (status, created_at) index is dropped in a LATER migration, once the plan gate (D12)
-- has run green in the cluster — never in this file.
--
-- Column note: 31 services already have claimed_at (ADD COLUMN IF NOT EXISTS is a no-op there);
-- next_attempt_at exists nowhere before this migration. NULL means "eligible now", which is
-- also what a v1 dispatcher effectively reads, so the two generations coexist on one table.

ALTER TABLE <t>_outbox ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;
ALTER TABLE <t>_outbox ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ;

-- Serves the claim's ORDER BY created_at, id under the rewritten status predicate (ADR-0327
-- finding 5: Index Scan at ~83 buffers instead of Seq Scan + Sort at ~163k).
CREATE INDEX IF NOT EXISTS ix_<t>_outbox_claim
    ON <t>_outbox (created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');

-- Serves the two per-aggregate anti-joins of the D3 head claim.
CREATE INDEX IF NOT EXISTS ix_<t>_outbox_inflight_aggregate
    ON <t>_outbox (aggregate_id, created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
