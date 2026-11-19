-- Statutory signature collection and immutable approval evidence.
-- Rollback: disable new collection and retain these tables for audit.
CREATE TABLE statutory_delegation_operations (
    id UUID PRIMARY KEY,
    action VARCHAR(8) NOT NULL CHECK (action IN ('ISSUE', 'ACCEPT')),
    delegation_id UUID NOT NULL,
    expected_lifecycle_revision BIGINT,
    principal_party_id UUID NOT NULL,
    initiator_party_id UUID NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    rule_snapshot TEXT NOT NULL,
    frozen_payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('COLLECTING', 'COMMITTED', 'REJECTED', 'CANCELLED')),
    closed_by UUID,
    CONSTRAINT uq_statutory_delegation_request UNIQUE (principal_party_id, initiator_party_id, action, request_key),
    CONSTRAINT ck_statutory_delegation_revision CHECK (
        (action = 'ISSUE' AND expected_lifecycle_revision IS NULL)
        OR (action = 'ACCEPT' AND expected_lifecycle_revision IS NOT NULL AND expected_lifecycle_revision >= 0)
    ),
    CONSTRAINT ck_statutory_delegation_window CHECK (expires_at > created_at),
    CONSTRAINT ck_statutory_delegation_closure CHECK (
        (status IN ('REJECTED', 'CANCELLED')) = (closed_by IS NOT NULL)
    )
);

CREATE INDEX idx_statutory_delegation_open ON statutory_delegation_operations (principal_party_id, expires_at)
WHERE status = 'COLLECTING';

CREATE TABLE statutory_delegation_signatures (
    operation_id UUID NOT NULL REFERENCES statutory_delegation_operations(id),
    actor_party_id UUID NOT NULL,
    sca_challenge_id UUID PRIMARY KEY,
    signed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_statutory_delegation_signer UNIQUE (operation_id, actor_party_id)
);


ALTER TABLE delegation_grants
    ADD COLUMN grantor_statutory_operation_id UUID REFERENCES statutory_delegation_operations(id),
    ADD COLUMN grantee_statutory_operation_id UUID REFERENCES statutory_delegation_operations(id),
    ADD CONSTRAINT ck_delegation_grantor_statutory_proof CHECK (
        grantor_statutory_operation_id IS NULL
        OR (grantor_signing_actor_id IS NULL AND grant_sca_session_id IS NULL)
    ),
    ADD CONSTRAINT ck_delegation_grantee_statutory_proof CHECK (
        grantee_statutory_operation_id IS NULL OR accept_sca_session_id IS NULL
    );

CREATE FUNCTION delegation_preserve_statutory_proof()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.grantor_statutory_operation_id IS DISTINCT FROM NEW.grantor_statutory_operation_id THEN
        RAISE EXCEPTION 'statutory issuer evidence is immutable for %', OLD.id;
    END IF;

    IF OLD.grantee_statutory_operation_id IS DISTINCT FROM NEW.grantee_statutory_operation_id
        AND NOT (
            OLD.grantee_statutory_operation_id IS NULL AND NEW.grantee_statutory_operation_id IS NOT NULL
            AND OLD.status = 'OFFERED' AND NEW.status = 'ACTIVE'
        ) THEN
        RAISE EXCEPTION 'statutory acceptance evidence is immutable for %', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_delegation_preserve_statutory_proof
    BEFORE UPDATE OF grantor_statutory_operation_id, grantee_statutory_operation_id ON delegation_grants
    FOR EACH ROW EXECUTE FUNCTION delegation_preserve_statutory_proof();
