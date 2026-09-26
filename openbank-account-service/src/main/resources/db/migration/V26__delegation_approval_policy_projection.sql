-- ADR-0232 D8: preserve the owner-selected approval policy in the enforcing account projection.
-- Existing rows predate enforceable multi-party approval and therefore truthfully default to SOLO.
-- Rollback:
--   ALTER TABLE account_delegation_projection DROP COLUMN required_approvals;
--   ALTER TABLE account_delegation_projection DROP COLUMN approval_policy;

ALTER TABLE account_delegation_projection
    ADD COLUMN approval_policy VARCHAR(16) NOT NULL DEFAULT 'SOLO',
    ADD COLUMN required_approvals INTEGER;

ALTER TABLE account_delegation_projection ALTER COLUMN approval_policy DROP DEFAULT;

ALTER TABLE account_delegation_projection ADD CONSTRAINT chk_delegation_projection_approval_policy CHECK (
    (approval_policy = 'N_OF_M' AND required_approvals >= 2)
    OR (approval_policy <> 'N_OF_M' AND required_approvals IS NULL)
);
