-- Add source scope without changing V10's Flyway checksum or inventing values for old rows.
-- A null environment/start time means the earlier observation cannot support incident correlation.
-- Rollback after retiring readers/writers: ALTER TABLE sepa_payment_workflow_observations
--   DROP COLUMN workflow_started_at, DROP COLUMN environment;
ALTER TABLE sepa_payment_workflow_observations
    ADD COLUMN environment VARCHAR(40),
    ADD COLUMN workflow_started_at TIMESTAMPTZ;

ALTER TABLE sepa_payment_workflow_observations
    ADD CONSTRAINT ck_sepa_workflow_observation_environment
    CHECK (environment IS NULL OR environment ~ '^[a-z][a-z0-9-]{0,39}$');
