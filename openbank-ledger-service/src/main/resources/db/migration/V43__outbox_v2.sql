-- ADR-0327 D2 and #11652. V18 already created claimed_at.
-- Expand only after the compatibility artifact is on every ledger pod. Keep the dispatcher
-- disabled until no pre-v2 dispatcher remains, as described in docs/runbooks/svc-ledger.md.
-- Rollback retains these nullable columns and indexes for the compatible v1 binary.
ALTER TABLE ledger_outbox
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claim_token UUID;

-- Keep the original indexes for the compatibility artifact and its rollback.
CREATE INDEX IF NOT EXISTS ix_ledger_outbox_claim
    ON ledger_outbox (created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');

CREATE INDEX IF NOT EXISTS ix_ledger_outbox_inflight_aggregate
    ON ledger_outbox (aggregate_id, created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
