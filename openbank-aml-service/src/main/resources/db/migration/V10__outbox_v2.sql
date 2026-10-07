-- ADR-0327 D2 — kernel outbox v2 (#11652): the one additive migration a service adds when its
-- repository moves onto AbstractPanacheOutboxRepository. Copied from
-- openbank-libs-runtime/src/main/resources/db/outbox-v2-template.sql.
--
-- Plain CREATE INDEX, never CONCURRENTLY: Quarkus runs each migration in one transaction. Both
-- indexes are PARTIAL (in-flight rows only), so the build reads the table once and writes a
-- small index. On a table past ~1 M rows an operator pre-creates both indexes by the same name
-- CONCURRENTLY before the deploy; IF NOT EXISTS then makes this file a no-op.
--
-- Rollback (before apply only — never edit an applied migration):
--   DROP INDEX IF EXISTS ix_aml_outbox_claim, ix_aml_outbox_inflight_aggregate;
--   ALTER TABLE aml_outbox DROP COLUMN IF EXISTS next_attempt_at;
-- The old (status, created_at) index is dropped in a LATER migration (ADR-0327 Phase 4).

ALTER TABLE aml_outbox ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;
ALTER TABLE aml_outbox ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ;

-- Serves the claim's ORDER BY created_at, id under the rewritten status predicate.
CREATE INDEX IF NOT EXISTS ix_aml_outbox_claim
    ON aml_outbox (created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');

-- Serves the two per-aggregate anti-joins of the D3 head claim.
CREATE INDEX IF NOT EXISTS ix_aml_outbox_inflight_aggregate
    ON aml_outbox (aggregate_id, created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
