-- ADR-0284 D3: legal-entity parties carry their legal form and registration country; a
-- representation mandate links a human (agent) to an entity (principal) they may act for.
-- Rollback:
--   DROP TABLE party_mandates; DROP SEQUENCE IF EXISTS party_mandates_seq;
--   ALTER TABLE parties DROP COLUMN legal_form, DROP COLUMN registration_country;

ALTER TABLE parties
    ADD COLUMN IF NOT EXISTS legal_form VARCHAR(32),
    ADD COLUMN IF NOT EXISTS registration_country CHAR(2);

CREATE TABLE party_mandates (
    id                  BIGSERIAL PRIMARY KEY,
    mandate_id          UUID NOT NULL UNIQUE,
    principal_party_id  UUID NOT NULL,
    agent_party_id      UUID NOT NULL,
    role                VARCHAR(32) NOT NULL,
    authority           VARCHAR(16) NOT NULL,
    source              VARCHAR(32) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    evidence_ref        VARCHAR(255),
    valid_from          TIMESTAMPTZ NOT NULL,
    valid_to            TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    revoke_reason       TEXT,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_party_mandates_agent ON party_mandates (agent_party_id, status);
CREATE INDEX idx_party_mandates_principal ON party_mandates (principal_party_id, status);

-- One ACTIVE mandate per (principal, agent, role): a re-grant updates the row instead of stacking.
CREATE UNIQUE INDEX uq_party_mandates_active ON party_mandates (principal_party_id, agent_party_id, role) WHERE status = 'ACTIVE';

-- Unquoted, lowercase, INCREMENT BY 50 — the convention V19 restored after V16/V18 got it wrong.
CREATE SEQUENCE IF NOT EXISTS party_mandates_seq INCREMENT BY 50;
