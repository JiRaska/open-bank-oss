-- Durable read evidence for case-scoped KYB ownership observations.
-- Rollback before any reads: DROP TABLE kyb_ubo_observation_reads;
-- After adoption retain the table under the KYB evidence policy; roll back code only.
CREATE TABLE kyb_ubo_observation_reads (
    read_id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES kyb_cases (case_id),
    observation_id UUID NOT NULL REFERENCES kyb_ubo_observations (observation_id),
    principal_id VARCHAR(128) NOT NULL,
    purpose VARCHAR(80) NOT NULL,
    read_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_kyb_ubo_observation_reads_case_time
    ON kyb_ubo_observation_reads (case_id, read_at DESC);

GRANT ALL ON kyb_ubo_observation_reads TO openbank;
