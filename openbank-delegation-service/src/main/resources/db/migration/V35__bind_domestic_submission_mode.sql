-- #12317: bind the original domestic submission key to one scope, payload and routing decision.
-- Rollback: revert the mode-aware application and retain the binding and its receipt link as evidence.
CREATE TABLE approval_submission_mode_claims (
    submission_key_digest VARCHAR(64) PRIMARY KEY CHECK (submission_key_digest ~ '^[0-9a-f]{64}$'),
    authority_digest VARCHAR(64) NOT NULL CHECK (authority_digest ~ '^[0-9a-f]{64}$'),
    entity_party_id UUID NOT NULL,
    initiator_party_id UUID NOT NULL,
    debit_account_id UUID NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    mode VARCHAR(6) NOT NULL CHECK (mode IN ('DIRECT', 'HELD')),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_submission_mode_authority UNIQUE (submission_key_digest, authority_digest, mode)
);

CREATE TRIGGER approval_submission_mode_immutable
BEFORE UPDATE OR DELETE ON approval_submission_mode_claims
FOR EACH ROW EXECUTE FUNCTION refuse_approval_submission_receipt_change();

ALTER TABLE approval_submission_receipts
    ADD COLUMN submission_key_digest VARCHAR(64) UNIQUE,
    ADD COLUMN bound_mode VARCHAR(6),
    ADD CONSTRAINT chk_approval_submission_bound_mode CHECK (
        (submission_key_digest IS NULL AND bound_mode IS NULL)
        OR (submission_key_digest IS NOT NULL AND bound_mode IS NOT NULL AND bound_mode = 'HELD')
    ),
    ADD CONSTRAINT fk_approval_submission_mode FOREIGN KEY (submission_key_digest, correlation_digest, bound_mode)
        REFERENCES approval_submission_mode_claims (submission_key_digest, authority_digest, mode);
