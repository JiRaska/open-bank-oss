-- PSD2 Consent Management Schema
-- Compliant with: PSD2 Art. 66/67, RTS 2018/389, GDPR Art. 7

CREATE TABLE consents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    party_id        UUID NOT NULL,
    grantee_id      VARCHAR(255) NOT NULL,
    grantee_type    VARCHAR(50) NOT NULL,
    grantee_name    VARCHAR(255) NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING_SCA',
    valid_from      TIMESTAMPTZ NOT NULL,
    valid_to        TIMESTAMPTZ NOT NULL,
    sca_session_id  UUID,
    redirect_uri    TEXT,
    tpp_transaction_id VARCHAR(255),
    ip_address      VARCHAR(45),
    user_agent      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at      TIMESTAMPTZ,
    revoked_reason  TEXT,
    CONSTRAINT chk_valid_to_after_from CHECK (valid_to > valid_from)
);

CREATE TABLE consent_scopes (
    consent_id  UUID NOT NULL REFERENCES consents(id) ON DELETE CASCADE,
    scope       VARCHAR(100) NOT NULL,
    PRIMARY KEY (consent_id, scope)
);

CREATE TABLE consent_accounts (
    consent_id  UUID NOT NULL REFERENCES consents(id) ON DELETE CASCADE,
    iban        VARCHAR(34) NOT NULL,
    PRIMARY KEY (consent_id, iban)
);

CREATE INDEX idx_consents_party_id ON consents(party_id);
CREATE INDEX idx_consents_grantee_id ON consents(grantee_id);
CREATE INDEX idx_consents_status ON consents(status);
CREATE INDEX idx_consents_valid_to ON consents(valid_to);
CREATE INDEX idx_consents_party_grantee ON consents(party_id, grantee_id);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
