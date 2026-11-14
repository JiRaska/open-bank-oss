-- Immutable count-pack SHADOW publication evidence. AML case decisions do not use this table.
-- Rollback: stop the shadow publisher and retain the signed evidence for audit.
CREATE TABLE aml_count_shadow_epoch (
    epoch_number BIGINT PRIMARY KEY CHECK (epoch_number > 0),
    previous_epoch_number BIGINT REFERENCES aml_count_shadow_epoch(epoch_number),
    proposal_id UUID NOT NULL UNIQUE,
    envelope_digest VARCHAR(64) NOT NULL CHECK (envelope_digest ~ '^[a-f0-9]{64}$'),
    envelope_json TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL UNIQUE,
    CONSTRAINT ck_aml_count_shadow_epoch_chain CHECK (
        (epoch_number = 1 AND previous_epoch_number IS NULL)
        OR (epoch_number > 1 AND previous_epoch_number IS NOT NULL AND previous_epoch_number = epoch_number - 1)
    ),
    CONSTRAINT ck_aml_count_shadow_epoch_time CHECK (recorded_at <= effective_at)
);

CREATE FUNCTION reject_aml_count_shadow_epoch_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'AML shadow epoch evidence is immutable';
END;
$$;

CREATE TRIGGER trg_aml_count_shadow_epoch_immutable
    BEFORE UPDATE OR DELETE ON aml_count_shadow_epoch
    FOR EACH ROW EXECUTE FUNCTION reject_aml_count_shadow_epoch_mutation();
