-- Delegated access schema (ADR-0232)
-- Customer-to-party delegation grants with capabilities, constraints and exposure.

CREATE TABLE delegation_grants (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grantor_party_id        UUID NOT NULL,
    grantee_party_id        UUID NOT NULL,
    resource_type           VARCHAR(50) NOT NULL,
    resource_id             UUID NOT NULL,
    approval_policy         VARCHAR(50) NOT NULL DEFAULT 'SOLO',
    required_approvals      INT,
    per_tx_limit_amount     NUMERIC(20, 6),
    per_tx_limit_currency   CHAR(3),
    daily_limit_amount      NUMERIC(20, 6),
    daily_limit_currency    CHAR(3),
    monthly_limit_amount    NUMERIC(20, 6),
    monthly_limit_currency  CHAR(3),
    max_views               INT,
    watermark               BOOLEAN,
    allow_download          BOOLEAN,
    valid_from              TIMESTAMPTZ NOT NULL,
    valid_to                TIMESTAMPTZ,
    status                  VARCHAR(50) NOT NULL DEFAULT 'OFFERED',
    grant_sca_session_id    UUID,
    accept_sca_session_id   UUID,
    note                    TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at               TIMESTAMPTZ,
    closed_by               UUID,
    closed_reason           TEXT,
    CONSTRAINT chk_delegation_parties_differ CHECK (grantor_party_id <> grantee_party_id),
    CONSTRAINT chk_delegation_valid_to_after_from CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT chk_delegation_n_of_m CHECK (approval_policy <> 'N_OF_M' OR required_approvals >= 2)
);

CREATE TABLE delegation_capabilities (
    grant_id    UUID NOT NULL REFERENCES delegation_grants(id) ON DELETE CASCADE,
    capability  VARCHAR(100) NOT NULL,
    PRIMARY KEY (grant_id, capability)
);

CREATE TABLE delegation_redaction_rules (
    grant_id    UUID NOT NULL REFERENCES delegation_grants(id) ON DELETE CASCADE,
    rule        VARCHAR(255) NOT NULL,
    PRIMARY KEY (grant_id, rule)
);

CREATE INDEX idx_delegation_grantor ON delegation_grants(grantor_party_id);
CREATE INDEX idx_delegation_grantee ON delegation_grants(grantee_party_id);
CREATE INDEX idx_delegation_resource ON delegation_grants(resource_type, resource_id);
CREATE INDEX idx_delegation_status ON delegation_grants(status);
CREATE INDEX idx_delegation_valid_to ON delegation_grants(valid_to);
CREATE INDEX idx_delegation_check ON delegation_grants(grantee_party_id, resource_type, resource_id, status);

-- Transactional outbox (ADR-0003 / ADR-0050), same shape as the rest of the fleet.
CREATE TABLE delegation_outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    attempt_count   INT NOT NULL DEFAULT 0,
    last_error      TEXT,
    claimed_at      TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_delegation_outbox_status ON delegation_outbox(status, created_at);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
