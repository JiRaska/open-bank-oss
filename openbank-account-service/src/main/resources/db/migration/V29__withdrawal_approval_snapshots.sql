-- ADR-0284 D3. Rollback: disable N_OF_M admission before dropping these additive structures.
ALTER TABLE account_delegation_projection
    ADD COLUMN approval_group_id UUID,
    ADD COLUMN approval_group_revision BIGINT,
    ADD CONSTRAINT chk_account_delegation_approval_group_binding CHECK (
        (approval_policy = 'N_OF_M' AND approval_group_id IS NOT NULL AND approval_group_revision >= 1)
        OR
        (approval_policy <> 'N_OF_M' AND approval_group_id IS NULL AND approval_group_revision IS NULL)
    ) NOT VALID;

ALTER TABLE savings_withdrawal_proposals
    ADD COLUMN delegation_grant_id UUID,
    ADD COLUMN approval_group_id UUID,
    ADD COLUMN approval_group_revision BIGINT,
    ADD COLUMN required_approvals INTEGER NOT NULL DEFAULT 1 CHECK (required_approvals >= 1);

CREATE TABLE savings_withdrawal_eligible_approvers (
    proposal_id UUID NOT NULL REFERENCES savings_withdrawal_proposals(id) ON DELETE CASCADE,
    party_id UUID NOT NULL,
    PRIMARY KEY (proposal_id, party_id)
);

CREATE TABLE savings_withdrawal_approval_decisions (
    proposal_id UUID NOT NULL REFERENCES savings_withdrawal_proposals(id) ON DELETE CASCADE,
    party_id UUID NOT NULL,
    approved BOOLEAN NOT NULL,
    sca_session_id UUID NOT NULL UNIQUE,
    decided_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (proposal_id, party_id)
);

CREATE INDEX idx_savings_withdrawal_decision_proposal
    ON savings_withdrawal_approval_decisions(proposal_id, approved);
