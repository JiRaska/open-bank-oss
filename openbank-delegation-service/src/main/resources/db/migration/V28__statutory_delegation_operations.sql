-- Expand-only evidence for a company delegation that needs statutory co-signatures.
-- V25-V27 are the pending owner-managed approval-group migrations: deploy those first.
-- This table does not enable JOINT issuance. An application version that does not know it
-- continues to refuse JOINT; no grant or event is produced by inserting a proposal.
--
-- The payload is canonical JSON text, not JSONB: the exact bytes are the SCA-bound operation
-- fingerprint. The rule snapshot is likewise immutable after creation. Decisions are separate,
-- unique by operation/person and SCA session, and cannot rewrite the signed proposal.
--
-- Rollback: disable the future proposal API and revert application images; retain both evidence
-- tables. A destructive drop, if ever required, needs a separate reviewed forward migration.
CREATE TABLE delegation_statutory_operations (
    operation_id             UUID PRIMARY KEY,
    principal_party_id       UUID NOT NULL,
    initiator_party_id       UUID NOT NULL,
    request_key              VARCHAR(200) NOT NULL,
    request_hash             CHAR(64) NOT NULL,
    payload_json             TEXT NOT NULL,
    policy_id                UUID NOT NULL,
    policy_revision          BIGINT NOT NULL,
    source_case_id           UUID NOT NULL,
    rule_hash                CHAR(64) NOT NULL,
    rule_snapshot_json       TEXT NOT NULL,
    state                    VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at               TIMESTAMPTZ NOT NULL,
    expires_at               TIMESTAMPTZ NOT NULL,
    executed_at              TIMESTAMPTZ,
    grant_id                 UUID,
    CONSTRAINT uq_delegation_statutory_request UNIQUE (principal_party_id, request_key),
    CONSTRAINT chk_delegation_statutory_hashes CHECK (
        request_hash ~ '^[0-9a-f]{64}$' AND rule_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_delegation_statutory_request_key CHECK (length(trim(request_key)) > 0),
    CONSTRAINT chk_delegation_statutory_policy_revision CHECK (policy_revision > 0),
    CONSTRAINT chk_delegation_statutory_payload_size CHECK (
        length(payload_json) BETWEEN 2 AND 65536 AND length(rule_snapshot_json) BETWEEN 2 AND 65536
    ),
    CONSTRAINT chk_delegation_statutory_state CHECK (state IN ('PENDING', 'EXECUTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_delegation_statutory_time CHECK (
        expires_at > created_at AND expires_at <= created_at + INTERVAL '7 days'
    ),
    CONSTRAINT chk_delegation_statutory_execution CHECK (
        (state = 'EXECUTED' AND executed_at IS NOT NULL AND grant_id IS NOT NULL)
        OR (state <> 'EXECUTED' AND executed_at IS NULL AND grant_id IS NULL)
    )
);

CREATE TABLE delegation_statutory_decisions (
    operation_id             UUID NOT NULL REFERENCES delegation_statutory_operations(operation_id),
    actor_party_id           UUID NOT NULL,
    sca_session_id           UUID NOT NULL,
    decision                VARCHAR(16) NOT NULL,
    decided_at               TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation_id, actor_party_id),
    CONSTRAINT uq_delegation_statutory_sca_session UNIQUE (sca_session_id),
    CONSTRAINT chk_delegation_statutory_decision CHECK (decision IN ('APPROVE', 'REJECT'))
);

CREATE INDEX idx_delegation_statutory_pending
    ON delegation_statutory_operations (principal_party_id, expires_at)
    WHERE state = 'PENDING';

CREATE OR REPLACE FUNCTION prevent_statutory_operation_evidence_rewrite()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'statutory operation evidence is immutable';
    END IF;
    IF (NEW.principal_party_id, NEW.initiator_party_id, NEW.request_key, NEW.request_hash,
        NEW.payload_json, NEW.policy_id, NEW.policy_revision, NEW.source_case_id,
        NEW.rule_hash, NEW.rule_snapshot_json, NEW.created_at, NEW.expires_at)
       IS DISTINCT FROM
       (OLD.principal_party_id, OLD.initiator_party_id, OLD.request_key, OLD.request_hash,
        OLD.payload_json, OLD.policy_id, OLD.policy_revision, OLD.source_case_id,
        OLD.rule_hash, OLD.rule_snapshot_json, OLD.created_at, OLD.expires_at) THEN
        RAISE EXCEPTION 'statutory operation evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER statutory_operation_evidence_immutable
    BEFORE UPDATE OR DELETE ON delegation_statutory_operations
    FOR EACH ROW EXECUTE FUNCTION prevent_statutory_operation_evidence_rewrite();

CREATE OR REPLACE FUNCTION prevent_statutory_decision_rewrite()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'statutory decision evidence is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER statutory_decision_evidence_immutable
    BEFORE UPDATE OR DELETE ON delegation_statutory_decisions
    FOR EACH ROW EXECUTE FUNCTION prevent_statutory_decision_rewrite();

GRANT ALL ON delegation_statutory_operations TO openbank;
GRANT ALL ON delegation_statutory_decisions TO openbank;
