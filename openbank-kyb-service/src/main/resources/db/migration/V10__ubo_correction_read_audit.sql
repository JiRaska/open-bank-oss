-- SPDX-License-Identifier: Apache-2.0
-- ADR-0305: every release of a pending correction candidate must leave a durable
-- case-, proposal-, principal- and purpose-scoped audit row before HTTP returns it.
-- Rollback before any reads: drop this table and its supporting case constraint;
-- after adoption, retain audit evidence under the KYB retention policy.
ALTER TABLE kyb_ubo_observation_corrections
    ADD CONSTRAINT uq_kyb_ubo_correction_case_id UNIQUE (case_id, correction_id);

CREATE TABLE kyb_ubo_correction_reads (
    read_id UUID PRIMARY KEY,
    case_id UUID NOT NULL,
    correction_id UUID NOT NULL,
    principal_id VARCHAR(128) NOT NULL CHECK (length(trim(principal_id)) > 0),
    purpose VARCHAR(80) NOT NULL CHECK (purpose = 'KYB_OWNERSHIP_REVIEW'),
    read_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_kyb_ubo_correction_read_case FOREIGN KEY (case_id, correction_id)
        REFERENCES kyb_ubo_observation_corrections(case_id, correction_id)
);
CREATE INDEX idx_kyb_ubo_correction_reads_case_time
    ON kyb_ubo_correction_reads(case_id, read_at DESC);

CREATE FUNCTION guard_kyb_ubo_correction_read_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'UBO correction read audit is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER kyb_ubo_correction_read_immutable
    BEFORE UPDATE OR DELETE ON kyb_ubo_correction_reads
    FOR EACH ROW EXECUTE FUNCTION guard_kyb_ubo_correction_read_mutation();
CREATE TRIGGER kyb_ubo_correction_read_no_truncate
    BEFORE TRUNCATE ON kyb_ubo_correction_reads
    FOR EACH STATEMENT EXECUTE FUNCTION guard_kyb_ubo_correction_read_mutation();

GRANT SELECT, INSERT ON kyb_ubo_correction_reads TO openbank;
