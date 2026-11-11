-- Append-only SHADOW publisher evidence. These tables never select the live fraud threshold set.
-- Rollback: stop the shadow reader and retain the epoch history for audit.
CREATE TABLE fraud_count_shadow_epoch (
    epoch_number BIGINT PRIMARY KEY CHECK (epoch_number > 0),
    previous_epoch_number BIGINT REFERENCES fraud_count_shadow_epoch(epoch_number),
    proposal_id UUID NOT NULL UNIQUE,
    envelope_digest CHAR(64) NOT NULL CHECK (envelope_digest ~ '^[a-f0-9]{64}$'),
    envelope_json TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_fraud_count_shadow_epoch_chain CHECK (
        (epoch_number = 1 AND previous_epoch_number IS NULL)
        OR (epoch_number > 1 AND previous_epoch_number IS NOT NULL AND previous_epoch_number = epoch_number - 1)
    ),
    CONSTRAINT ck_fraud_count_shadow_epoch_time CHECK (recorded_at <= effective_at)
);

CREATE INDEX idx_fraud_count_shadow_epoch_effective ON fraud_count_shadow_epoch (effective_at DESC);

CREATE TABLE fraud_count_shadow_epoch_head (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    epoch_number BIGINT REFERENCES fraud_count_shadow_epoch(epoch_number) DEFERRABLE INITIALLY DEFERRED
);

INSERT INTO fraud_count_shadow_epoch_head (singleton_id) VALUES (1);

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


CREATE FUNCTION advance_fraud_count_shadow_epoch_head()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.epoch_number IS DISTINCT FROM COALESCE(OLD.epoch_number, 0) + 1 THEN
        RAISE EXCEPTION 'fraud shadow head must advance exactly one epoch';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_fraud_count_shadow_epoch_head_advance
    BEFORE UPDATE OF epoch_number ON fraud_count_shadow_epoch_head
    FOR EACH ROW EXECUTE FUNCTION advance_fraud_count_shadow_epoch_head();
