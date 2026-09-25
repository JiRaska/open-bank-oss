-- Source-owned, append-only workflow outcomes for later evidence-backed incident correlation.
-- A status transition is an observation, never proof that an ICT incident caused it.
-- Rollback before writes: DROP TABLE sepa_payment_workflow_observations;
-- After writes, retain the rows for the source evidence period before retiring the reader.
CREATE TABLE sepa_payment_workflow_observations (
    event_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    payment_revision BIGINT NOT NULL CHECK (payment_revision >= 0),
    event_type VARCHAR(128) NOT NULL,
    payment_status VARCHAR(40) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    synthetic BOOLEAN NOT NULL DEFAULT FALSE,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sepa_workflow_observation_revision UNIQUE (payment_id, payment_revision),
    CONSTRAINT fk_sepa_workflow_observation_payment FOREIGN KEY (payment_id)
        REFERENCES sepa_payments(payment_id) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_sepa_workflow_observation_payment_history
    ON sepa_payment_workflow_observations (payment_id, payment_revision DESC);

CREATE FUNCTION reject_sepa_workflow_observation_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'SEPA workflow observations are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sepa_workflow_observations_append_only
    BEFORE UPDATE OR DELETE ON sepa_payment_workflow_observations
    FOR EACH ROW EXECUTE FUNCTION reject_sepa_workflow_observation_mutation();
