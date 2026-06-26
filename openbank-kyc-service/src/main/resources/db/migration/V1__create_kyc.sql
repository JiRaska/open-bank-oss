CREATE TABLE kyc_cases (
    id          BIGSERIAL PRIMARY KEY,
    case_id     UUID        NOT NULL UNIQUE,
    party_id    UUID        NOT NULL,
    status      VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    risk_level  VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    assigned_to VARCHAR(100),
    checks_json TEXT        NOT NULL DEFAULT '[]',
    notes       TEXT,
    reviewed_by VARCHAR(100),
    reviewed_at TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_kyc_cases_party_id ON kyc_cases(party_id);
CREATE INDEX idx_kyc_cases_status ON kyc_cases(status);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
