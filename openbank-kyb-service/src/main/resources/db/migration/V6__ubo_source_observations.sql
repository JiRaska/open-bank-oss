-- Case-scoped, append-only source observations for ADR-0305. The mapped finding stays in KYB;
-- the future dedicated Context event may carry only observation_id, revision and source_sha256.
-- No event producer or read API is enabled by this migration.
--
-- Rollback before any real observations exist: DROP TABLE kyb_ubo_observations;
-- After adoption, retain the table under the KYB evidence-retention policy and roll back code
-- only; dropping recorded observations would destroy historical decision evidence.
CREATE TABLE kyb_ubo_observations (
    observation_id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES kyb_cases (case_id),
    revision BIGINT NOT NULL CHECK (revision > 0),
    source VARCHAR(32) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    finding_json TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_kyb_ubo_observation_case_revision UNIQUE (case_id, revision),
    CONSTRAINT chk_kyb_ubo_observation_size CHECK (octet_length(finding_json) <= 262144)
);

CREATE INDEX idx_kyb_ubo_observation_case_recorded
    ON kyb_ubo_observations (case_id, recorded_at DESC);

GRANT ALL ON kyb_ubo_observations TO openbank;
