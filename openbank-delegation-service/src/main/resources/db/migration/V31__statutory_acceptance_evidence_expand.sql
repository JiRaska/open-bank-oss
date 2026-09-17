-- Expand the immutable JOINT operation ledger to represent acceptance of an existing OFFERED
-- grant. Existing issuance rows get ISSUE without a table rewrite; old application images keep
-- rejecting JOINT acceptance and never create ACCEPT rows. V32 may validate NOT VALID constraints
-- after a separate scan. Rollback: turn off the acceptance API and keep all evidence columns,
-- constraints, triggers and rows. Never re-interpret an ACCEPT row as an ISSUE row.
ALTER TABLE delegation_statutory_operations
    ADD COLUMN operation_kind VARCHAR(16) NOT NULL DEFAULT 'ISSUE',
    ADD COLUMN target_grant_id UUID,
    ADD COLUMN expected_lifecycle_revision BIGINT;

ALTER TABLE delegation_statutory_operations
    ADD CONSTRAINT chk_delegation_statutory_kind
        CHECK (operation_kind IN ('ISSUE', 'ACCEPT')) NOT VALID,
    ADD CONSTRAINT chk_delegation_statutory_accept_target
        CHECK (
            (operation_kind = 'ISSUE' AND target_grant_id IS NULL AND expected_lifecycle_revision IS NULL)
            OR (operation_kind = 'ACCEPT' AND target_grant_id IS NOT NULL
                AND expected_lifecycle_revision >= 0)
        ) NOT VALID,
    ADD CONSTRAINT chk_delegation_statutory_accept_result
        CHECK (operation_kind <> 'ACCEPT' OR state <> 'EXECUTED' OR grant_id = target_grant_id) NOT VALID,
    ADD CONSTRAINT fk_delegation_statutory_target_grant
        FOREIGN KEY (target_grant_id) REFERENCES delegation_grants(id) NOT VALID;

CREATE INDEX idx_delegation_statutory_accept_target
    ON delegation_statutory_operations (target_grant_id, created_at DESC)
    WHERE operation_kind = 'ACCEPT';

-- V28's immutable-evidence trigger checks all original columns. This complementary trigger
-- protects only the new discriminator and target; state/grant_id remain the intended mutable
-- execution markers.
CREATE FUNCTION prevent_statutory_acceptance_target_rewrite()
RETURNS trigger AS $$
BEGIN
    IF ROW(NEW.operation_kind, NEW.target_grant_id, NEW.expected_lifecycle_revision)
       IS DISTINCT FROM
       ROW(OLD.operation_kind, OLD.target_grant_id, OLD.expected_lifecycle_revision) THEN
        RAISE EXCEPTION 'statutory acceptance target is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER statutory_acceptance_target_immutable
    BEFORE UPDATE OF operation_kind, target_grant_id, expected_lifecycle_revision
    ON delegation_statutory_operations
    FOR EACH ROW EXECUTE FUNCTION prevent_statutory_acceptance_target_rewrite();

ALTER TABLE delegation_grants
    ADD COLUMN accept_statutory_operation_id UUID;

ALTER TABLE delegation_grants
    ADD CONSTRAINT uq_delegation_accept_statutory_operation UNIQUE (accept_statutory_operation_id),
    ADD CONSTRAINT fk_delegation_accept_statutory_operation
        FOREIGN KEY (accept_statutory_operation_id)
        REFERENCES delegation_statutory_operations(operation_id) NOT VALID,
    ADD CONSTRAINT chk_delegation_acceptance_proof_exclusive
        CHECK (accept_sca_session_id IS NULL OR accept_statutory_operation_id IS NULL) NOT VALID;

CREATE FUNCTION prevent_statutory_acceptance_proof_rewrite()
RETURNS trigger AS $$
BEGIN
    IF OLD.accept_statutory_operation_id IS NOT NULL
       AND NEW.accept_statutory_operation_id IS DISTINCT FROM OLD.accept_statutory_operation_id THEN
        RAISE EXCEPTION 'statutory acceptance evidence is immutable';
    END IF;
    IF OLD.accept_statutory_operation_id IS NULL
       AND NEW.accept_statutory_operation_id IS NOT NULL
       AND NOT (OLD.status = 'OFFERED' AND NEW.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'statutory acceptance proof requires OFFERED to ACTIVE';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER statutory_acceptance_proof_immutable
    BEFORE UPDATE OF accept_statutory_operation_id ON delegation_grants
    FOR EACH ROW EXECUTE FUNCTION prevent_statutory_acceptance_proof_rewrite();
