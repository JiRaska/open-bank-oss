-- ADR-0284 D3 / #10247: additive, immutable statutory-rule evidence. Historical mandates are
-- untouched and gain no inferred rule; readers must continue to refuse incomplete JOINT authority.
-- Rollback: stop new policy writes and retain this table as evidence. Drop it only after separately
-- authorized archival and after all readers have been rolled back; do not delete signed evidence
-- merely to roll back the application image.

-- The direct-SQL default and Hibernate's pooled allocator must draw from the SAME sequence.
-- Two independent sequences can both issue id=1 and fail a legitimate second insert.
CREATE SEQUENCE party_representation_policies_seq INCREMENT BY 50;

CREATE TABLE party_representation_policies (
    id                            BIGINT DEFAULT nextval('party_representation_policies_seq') PRIMARY KEY,
    policy_id                     UUID NOT NULL UNIQUE,
    principal_party_id            UUID NOT NULL,
    revision                      BIGINT NOT NULL CHECK (revision > 0),
    source_case_id                UUID NOT NULL UNIQUE,
    attestation_id                UUID NOT NULL,
    rule_text_hash                CHAR(64) NOT NULL CHECK (rule_text_hash ~ '^[0-9a-f]{64}$'),
    registry_source               VARCHAR(64) NOT NULL,
    registry_source_ref           VARCHAR(255),
    mode                          VARCHAR(16) NOT NULL CHECK (mode IN ('SOLE', 'JOINT_N', 'JOINT_ALL')),
    required_signatures           INTEGER NOT NULL CHECK (required_signatures > 0),
    required_offices_json         TEXT NOT NULL CHECK (jsonb_typeof(required_offices_json::jsonb) = 'array'),
    eligible_representatives_json TEXT NOT NULL CHECK (
        jsonb_typeof(eligible_representatives_json::jsonb) = 'array'
        AND jsonb_array_length(eligible_representatives_json::jsonb) >= required_signatures
    ),
    evidence_ref                  VARCHAR(255) NOT NULL,
    effective_from                TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_party_representation_policy_revision UNIQUE (principal_party_id, revision)
);

CREATE INDEX idx_party_representation_policy_principal
    ON party_representation_policies (principal_party_id, revision DESC);

ALTER SEQUENCE party_representation_policies_seq OWNED BY party_representation_policies.id;

-- A signed representation rule is evidence, not mutable profile configuration. A new verified
-- rule creates a new revision; neither application bugs nor manual SQL may rewrite the old one.
CREATE FUNCTION refuse_representation_policy_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'representation policy snapshots are append-only' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_party_representation_policy_immutable
    BEFORE UPDATE OR DELETE ON party_representation_policies
    FOR EACH ROW EXECUTE FUNCTION refuse_representation_policy_mutation();
