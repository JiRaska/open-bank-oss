-- ADR-0284 D3: local representation projection for SCA-derived human actors on entity accounts.
-- Rollback: DROP TABLE account_party_mandate_projection;

CREATE TABLE account_party_mandate_projection (
    mandate_id UUID PRIMARY KEY,
    principal_party_id UUID NOT NULL,
    agent_party_id UUID NOT NULL,
    authority VARCHAR(16) NOT NULL,
    required_signatures INTEGER,
    active BOOLEAN NOT NULL,
    CONSTRAINT chk_account_mandate_quorum CHECK (
        required_signatures IS NULL
        OR (authority = 'SOLE' AND required_signatures = 1)
        OR (authority = 'JOINT' AND required_signatures >= 2)
    )
);

CREATE INDEX idx_account_mandate_actor
    ON account_party_mandate_projection(principal_party_id, agent_party_id, active);
