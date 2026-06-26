CREATE TABLE aml_cases (
    id BIGSERIAL PRIMARY KEY,
    case_id UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    party_id UUID NOT NULL,
    account_id UUID,
    transaction_id UUID,
    customer_reference VARCHAR(128) NOT NULL,
    screening_type VARCHAR(64) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    alert_code VARCHAR(64) NOT NULL,
    alert_detail TEXT,
    matched_entity VARCHAR(255),
    decision_reason TEXT,
    assigned_analyst VARCHAR(128),
    decided_by VARCHAR(128),
    screened_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_aml_cases_party_id ON aml_cases(party_id);
CREATE INDEX idx_aml_cases_status ON aml_cases(status);
CREATE INDEX idx_aml_cases_screening_type ON aml_cases(screening_type);
CREATE INDEX idx_aml_cases_created_at ON aml_cases(created_at DESC);
