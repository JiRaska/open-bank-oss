-- Append-only SHADOW publisher evidence. These rows never select the live fraud threshold set.
-- One writer per epoch is decided by the primary key; the predecessor foreign key plus the
-- epoch - 1 check forbid a gap; a unique proposal id forbids reuse. No head row, no lock.
-- Rollback: stop the shadow reader and retain the epoch history for audit.
CREATE TABLE fraud_count_shadow_epoch (
    epoch_number BIGINT PRIMARY KEY CHECK (epoch_number > 0),
    previous_epoch_number BIGINT REFERENCES fraud_count_shadow_epoch(epoch_number),
    proposal_id UUID NOT NULL UNIQUE,
    envelope_digest CHAR(64) NOT NULL CHECK (envelope_digest ~ '^[a-f0-9]{64}$'),
    envelope_json TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL UNIQUE,
    CONSTRAINT ck_fraud_count_shadow_epoch_chain CHECK (
        (epoch_number = 1 AND previous_epoch_number IS NULL)
        OR (epoch_number > 1 AND previous_epoch_number IS NOT NULL AND previous_epoch_number = epoch_number - 1)
    ),
    CONSTRAINT ck_fraud_count_shadow_epoch_time CHECK (recorded_at <= effective_at)
);

CREATE FUNCTION reject_fraud_count_shadow_epoch_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'fraud shadow epoch evidence is immutable';
END;
$$;

CREATE TRIGGER trg_fraud_count_shadow_epoch_immutable
    BEFORE UPDATE OR DELETE ON fraud_count_shadow_epoch
    FOR EACH ROW EXECUTE FUNCTION reject_fraud_count_shadow_epoch_mutation();
