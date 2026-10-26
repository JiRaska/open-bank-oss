-- Atomic, source-linked replay binding. Reuses the existing 86400-second idempotency lifetime;
-- never copies a financial response into an independently retained store.
CREATE TABLE journal_history_capture_receipts (
    scope_key_hash VARCHAR(64) PRIMARY KEY CHECK (scope_key_hash ~ '^[0-9a-f]{64}$'),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    result_kind VARCHAR(12) NOT NULL CHECK (result_kind IN ('PERSISTED','EMPTY')),
    snapshot_id UUID REFERENCES journal_history_snapshots(id) ON DELETE SET NULL,
    empty_result_id UUID,
    empty_observed_at TIMESTAMPTZ,
    empty_database_view TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (statement_timestamp() + interval '86400 seconds'),
    CHECK (expires_at = created_at + interval '86400 seconds'),
    CHECK ((result_kind = 'PERSISTED' AND empty_result_id IS NULL AND empty_observed_at IS NULL
            AND empty_database_view IS NULL)
        OR (result_kind = 'EMPTY' AND snapshot_id IS NULL AND empty_result_id IS NOT NULL
            AND empty_observed_at IS NOT NULL AND empty_database_view IS NOT NULL))
);
CREATE INDEX idx_history_capture_receipts_expiry ON journal_history_capture_receipts(expires_at);

-- A source deletion may clear its reference, never renew the key or change the request binding.
CREATE FUNCTION protect_history_capture_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.result_kind = 'PERSISTED' AND OLD.snapshot_id IS NOT NULL AND NEW.snapshot_id IS NULL
        AND (to_jsonb(OLD) - 'snapshot_id') = (to_jsonb(NEW) - 'snapshot_id') THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'history capture replay bindings are immutable' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER history_capture_receipt_no_update BEFORE UPDATE ON journal_history_capture_receipts
    FOR EACH ROW EXECUTE FUNCTION protect_history_capture_receipt();

-- Expired bindings are inaccessible at expires_at and purged by the minute maintenance job or
-- on access. Physical removal can lag while the service is stopped; no perpetual tombstone or
-- additional retention period is introduced. Digests/opaque IDs are not claimed to be anonymous.
-- Rollback: disable this additive route, drop its receipt table/function in a reviewed migration.
-- Preserve financial journals, source cache lifecycle, posting idempotency and legal holds.
