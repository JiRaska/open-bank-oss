-- SPDX-License-Identifier: Apache-2.0
-- ADR-0305 expand stage: explicit mapped-finding correction, never an inferred legal
-- effective date. No writer or event is enabled by this migration.
-- Rollback before the first proposal: drop V9 triggers/functions, the two new
-- observation columns and case-ID uniqueness, then the proposal table. After adoption, disable writers
-- and retain evidence; dropping populated corrections is not deployment rollback.

ALTER TABLE kyb_ubo_observations
    ADD CONSTRAINT uq_kyb_ubo_observation_case_id UNIQUE (case_id, observation_id);

CREATE TABLE kyb_ubo_observation_corrections (
    correction_id UUID PRIMARY KEY,
    case_id UUID NOT NULL,
    prior_observation_id UUID NOT NULL,
    candidate_finding_json TEXT NOT NULL,
    candidate_sha256 CHAR(64) NOT NULL CHECK (candidate_sha256 ~ '^[0-9a-f]{64}$'),
    candidate_source VARCHAR(32) NOT NULL CHECK (candidate_source = 'REGISTER'),
    candidate_fetched_at TIMESTAMPTZ NOT NULL,
    reason_code VARCHAR(32) NOT NULL CHECK (reason_code IN
        ('REGISTER_CORRECTION', 'MAPPING_ERROR', 'SOURCE_RECORD_AMENDED')),
    proposed_by VARCHAR(128) NOT NULL CHECK (length(trim(proposed_by)) > 0),
    proposed_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by VARCHAR(128),
    decided_at TIMESTAMPTZ,
    CONSTRAINT fk_kyb_ubo_correction_prior_case FOREIGN KEY (case_id, prior_observation_id)
        REFERENCES kyb_ubo_observations(case_id, observation_id),
    CONSTRAINT chk_kyb_ubo_correction_size CHECK (octet_length(candidate_finding_json) <= 262144),
    CONSTRAINT chk_kyb_ubo_correction_hash CHECK
        (candidate_sha256 = encode(sha256(convert_to(candidate_finding_json, 'UTF8')), 'hex')),
    CONSTRAINT chk_kyb_ubo_correction_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL) OR
        (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decided_by <> proposed_by AND decided_at >= proposed_at)
    )
);
CREATE INDEX idx_kyb_ubo_correction_case_pending
    ON kyb_ubo_observation_corrections(case_id, proposed_at DESC)
    WHERE status = 'PENDING';

CREATE FUNCTION guard_kyb_ubo_correction_proposal() RETURNS trigger AS $$
DECLARE
    prior_hash CHAR(64);
    prior_revision BIGINT;
BEGIN
    IF NEW.status <> 'PENDING' OR NEW.decided_by IS NOT NULL OR NEW.decided_at IS NOT NULL THEN
        RAISE EXCEPTION 'correction proposal must start pending';
    END IF;
    SELECT source_sha256, revision INTO prior_hash, prior_revision
      FROM kyb_ubo_observations
      WHERE case_id = NEW.case_id AND observation_id = NEW.prior_observation_id;
    IF prior_hash IS NOT NULL AND prior_hash = NEW.candidate_sha256 THEN
        RAISE EXCEPTION 'correction cannot repeat the prior mapped finding';
    END IF;
    IF prior_revision IS DISTINCT FROM (
        SELECT max(revision) FROM kyb_ubo_observations WHERE case_id = NEW.case_id
    ) THEN
        RAISE EXCEPTION 'correction must target the latest observation revision';
    END IF;
    IF EXISTS (
        SELECT 1 FROM kyb_ubo_observation_restrictions
        WHERE observation_id = NEW.prior_observation_id
    ) THEN
        RAISE EXCEPTION 'restricted observation cannot be corrected';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER kyb_ubo_correction_proposal_guard
    BEFORE INSERT ON kyb_ubo_observation_corrections
    FOR EACH ROW EXECUTE FUNCTION guard_kyb_ubo_correction_proposal();

ALTER TABLE kyb_ubo_observations
    ADD COLUMN supersedes_observation_id UUID,
    ADD COLUMN correction_id UUID UNIQUE REFERENCES kyb_ubo_observation_corrections(correction_id),
    ADD CONSTRAINT uq_kyb_ubo_observation_supersedes UNIQUE (supersedes_observation_id),
    ADD CONSTRAINT fk_kyb_ubo_observation_prior_case
        FOREIGN KEY (case_id, supersedes_observation_id)
        REFERENCES kyb_ubo_observations(case_id, observation_id),
    ADD CONSTRAINT chk_kyb_ubo_observation_correction_pair CHECK
        ((supersedes_observation_id IS NULL) = (correction_id IS NULL));

CREATE FUNCTION guard_kyb_ubo_observation_correction() RETURNS trigger AS $$
DECLARE
    prior_revision BIGINT;
    prior_restricted BOOLEAN;
    proposal_case_id UUID;
    proposal_prior_id UUID;
    proposal_status VARCHAR(16);
    proposal_hash CHAR(64);
    proposal_json TEXT;
    proposal_source VARCHAR(32);
    proposal_fetched_at TIMESTAMPTZ;
BEGIN
    IF NEW.correction_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT o.revision, EXISTS (
        SELECT 1 FROM kyb_ubo_observation_restrictions r
        WHERE r.observation_id = o.observation_id
    ) INTO prior_revision, prior_restricted
      FROM kyb_ubo_observations o WHERE o.observation_id = NEW.supersedes_observation_id;
    IF prior_revision IS DISTINCT FROM NEW.revision - 1 OR prior_restricted THEN
        RAISE EXCEPTION 'correction must follow the latest unrestricted observation revision';
    END IF;
    SELECT case_id, prior_observation_id, status, candidate_sha256,
           candidate_finding_json, candidate_source, candidate_fetched_at
      INTO proposal_case_id, proposal_prior_id, proposal_status, proposal_hash,
           proposal_json, proposal_source, proposal_fetched_at
      FROM kyb_ubo_observation_corrections WHERE correction_id = NEW.correction_id;
    IF proposal_case_id IS DISTINCT FROM NEW.case_id OR
       proposal_prior_id IS DISTINCT FROM NEW.supersedes_observation_id OR
       proposal_status IS DISTINCT FROM 'APPROVED' OR
       proposal_hash IS DISTINCT FROM NEW.source_sha256 OR
       proposal_json IS DISTINCT FROM NEW.finding_json OR
       proposal_source IS DISTINCT FROM NEW.source OR
       proposal_fetched_at IS DISTINCT FROM NEW.fetched_at THEN
        RAISE EXCEPTION 'approved correction proposal must match the replacement observation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER kyb_ubo_observation_correction_guard
    BEFORE INSERT ON kyb_ubo_observations
    FOR EACH ROW EXECUTE FUNCTION guard_kyb_ubo_observation_correction();

CREATE FUNCTION guard_kyb_ubo_correction_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'UBO correction evidence cannot be truncated';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'UBO correction evidence cannot be deleted';
    END IF;
    IF OLD.status <> 'PENDING' OR NEW.status = 'PENDING' OR
       (to_jsonb(NEW) - 'status' - 'decided_by' - 'decided_at') <>
       (to_jsonb(OLD) - 'status' - 'decided_by' - 'decided_at') THEN
        RAISE EXCEPTION 'UBO correction proposal is immutable except for its decision';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER kyb_ubo_correction_immutable
    BEFORE UPDATE OR DELETE ON kyb_ubo_observation_corrections
    FOR EACH ROW EXECUTE FUNCTION guard_kyb_ubo_correction_mutation();
CREATE TRIGGER kyb_ubo_correction_no_truncate
    BEFORE TRUNCATE ON kyb_ubo_observation_corrections
    FOR EACH STATEMENT EXECUTE FUNCTION guard_kyb_ubo_correction_mutation();

-- A decision cannot commit as APPROVED without the replacement observation.
-- The writer updates the proposal and inserts that observation in one transaction.
CREATE FUNCTION require_kyb_ubo_correction_replacement() RETURNS trigger AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM kyb_ubo_observations
        WHERE correction_id = NEW.correction_id
    ) THEN
        RAISE EXCEPTION 'approved correction requires a replacement observation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER kyb_ubo_correction_has_replacement
    AFTER UPDATE ON kyb_ubo_observation_corrections
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status = 'APPROVED')
    EXECUTE FUNCTION require_kyb_ubo_correction_replacement();

GRANT SELECT, INSERT, UPDATE ON kyb_ubo_observation_corrections TO openbank;
