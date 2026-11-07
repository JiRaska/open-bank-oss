-- #13623: keep a received return recoverable until the original debit has a proven reversal.
-- Nullable/no backfill: legacy RETURNED rows are not retroactively asserted refunded.
-- Rollback: roll back the reader/writer code first. Export and reconcile ALL unresolved rows
-- before dropping these columns: losing the intent loses pending refund identity and age.
-- Do not delete intents or change keys to make an unresolved refund look complete.
ALTER TABLE sepa_payments
    ADD COLUMN return_original_transaction_id UUID,
    ADD COLUMN return_first_received_at TIMESTAMPTZ,
    ADD COLUMN return_reason_code TEXT,
    ADD COLUMN return_actor_id TEXT,
    ADD COLUMN return_actor_type TEXT,
    ADD COLUMN return_correlation_id TEXT,
    ADD COLUMN return_unresolved_cause TEXT,
    ADD COLUMN return_resolved_at TIMESTAMPTZ,
    ADD COLUMN return_reversal_transaction_id UUID;
CREATE INDEX idx_sepa_unresolved_return_age ON sepa_payments (return_first_received_at)
    WHERE return_first_received_at IS NOT NULL AND return_resolved_at IS NULL;

ALTER TABLE sepa_payments ADD CONSTRAINT sepa_return_resolved_identity CHECK (
    return_resolved_at IS NULL OR
    (return_first_received_at IS NOT NULL AND return_original_transaction_id IS NOT NULL
     AND return_reversal_transaction_id IS NOT NULL AND status = 'RETURNED')
);
-- Defense in depth; application/domain preflight rejects this before a database error.
ALTER TABLE sepa_payments ADD CONSTRAINT sepa_pending_return_recoverable CHECK (
    return_first_received_at IS NULL OR return_resolved_at IS NOT NULL
    OR status IN ('PROCESSING', 'COMPLETED')
);
-- After exporting/reconciling pending intents and rolling back the application, rollback SQL:
-- DROP INDEX idx_sepa_unresolved_return_age;
-- ALTER TABLE sepa_payments DROP CONSTRAINT sepa_return_resolved_identity,
-- DROP CONSTRAINT sepa_pending_return_recoverable,
-- DROP COLUMN return_original_transaction_id, DROP COLUMN return_first_received_at,
-- DROP COLUMN return_reason_code, DROP COLUMN return_actor_id, DROP COLUMN return_actor_type,
-- DROP COLUMN return_correlation_id, DROP COLUMN return_unresolved_cause,
-- DROP COLUMN return_resolved_at, DROP COLUMN return_reversal_transaction_id;
