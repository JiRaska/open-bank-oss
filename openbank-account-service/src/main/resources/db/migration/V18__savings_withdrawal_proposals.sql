-- Propose-only withdrawal proposals (ADR-0232 D8 / issue #2990 AC8).
-- Rollback: DROP TABLE savings_withdrawal_proposals;
CREATE TABLE savings_withdrawal_proposals (
    id                  UUID PRIMARY KEY,
    account_id          UUID NOT NULL,
    delegate_party_id   UUID NOT NULL,
    amount_minor        BIGINT NOT NULL,
    currency            CHAR(3) NOT NULL,
    note                TEXT,
    status              VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    approval_id         VARCHAR(128),
    decided_by          UUID,
    decided_at          TIMESTAMPTZ,
    sca_session_id      UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_proposal_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX idx_proposals_account_status ON savings_withdrawal_proposals(account_id, status);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
