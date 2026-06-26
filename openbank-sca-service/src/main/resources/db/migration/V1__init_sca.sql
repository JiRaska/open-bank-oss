CREATE TABLE sca_challenges (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    party_id                UUID NOT NULL,
    purpose                 VARCHAR(50) NOT NULL,
    method                  VARCHAR(50) NOT NULL,
    status                  VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    expires_at              TIMESTAMPTZ NOT NULL,
    completed_at            TIMESTAMPTZ,
    failed_at               TIMESTAMPTZ,
    failure_reason          TEXT,
    attempt_count           INT NOT NULL DEFAULT 0,
    max_attempts            INT NOT NULL DEFAULT 3,
    dynamic_amount          VARCHAR(30),
    dynamic_currency        VARCHAR(3),
    dynamic_creditor_iban   VARCHAR(34),
    dynamic_creditor_name   VARCHAR(255),
    dynamic_reference       VARCHAR(255),
    redirect_url            TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sca_party_id ON sca_challenges(party_id);
CREATE INDEX idx_sca_status ON sca_challenges(status);
CREATE INDEX idx_sca_expires_at ON sca_challenges(expires_at);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
