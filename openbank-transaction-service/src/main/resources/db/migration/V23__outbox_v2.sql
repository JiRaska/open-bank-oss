-- ADR-0327 D2 and #11652. V15 already created claimed_at.
-- Expand only after the compatibility artifact is on every transaction pod. Keep the dispatcher
-- disabled until no pre-v2 dispatcher remains, as described in docs/runbooks/transaction-outbox-v2-cutover.md.
-- Rollback retains these nullable columns and indexes for the compatible v1 binary.
ALTER TABLE transaction_outbox
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claim_token UUID;

-- Keep the original indexes for the compatibility artifact and its rollback.
CREATE INDEX IF NOT EXISTS ix_transaction_outbox_claim
    ON transaction_outbox (created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');

CREATE INDEX IF NOT EXISTS ix_transaction_outbox_inflight_aggregate
    ON transaction_outbox (aggregate_id, created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
