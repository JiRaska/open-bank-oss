-- ADR-0327 D2 and #11652. V6 already created claimed_at.
-- Expand only after the compatibility artifact is on every domestic pod. Keep the dispatcher
-- disabled until no pre-v2 dispatcher remains, as described in docs/runbooks/domestic-payment-outbox-v2-cutover.md.
-- Rollback retains these nullable columns and indexes for the compatible v1 binary.
ALTER TABLE domestic_payment_outbox
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claim_token UUID;

-- Keep the original indexes for the compatibility artifact and its rollback.
CREATE INDEX IF NOT EXISTS ix_domestic_payment_outbox_claim
    ON domestic_payment_outbox (created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');

CREATE INDEX IF NOT EXISTS ix_domestic_payment_outbox_inflight_aggregate
    ON domestic_payment_outbox (aggregate_id, created_at, id)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');
