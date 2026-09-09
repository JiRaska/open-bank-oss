-- ADR-0284 D3. Rollback: drop the two nullable columns after N_OF_M admission is disabled.
ALTER TABLE delegation_grants
    ADD COLUMN approval_group_id UUID,
    ADD COLUMN approval_group_revision BIGINT;

ALTER TABLE delegation_grants
    ADD CONSTRAINT fk_delegation_grant_approval_group_revision
        FOREIGN KEY (approval_group_id, approval_group_revision)
        REFERENCES delegation_approval_group_revisions(group_id, revision),
    ADD CONSTRAINT chk_delegation_grant_approval_group_binding
        CHECK (
            (approval_policy = 'N_OF_M' AND approval_group_id IS NOT NULL AND approval_group_revision >= 1)
            OR
            (approval_policy <> 'N_OF_M' AND approval_group_id IS NULL AND approval_group_revision IS NULL)
        ) NOT VALID;

CREATE INDEX idx_delegation_grant_approval_group_revision
    ON delegation_grants(approval_group_id, approval_group_revision)
    WHERE approval_group_id IS NOT NULL;
