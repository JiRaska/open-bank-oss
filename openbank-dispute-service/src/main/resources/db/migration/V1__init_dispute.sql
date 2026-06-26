CREATE TYPE dispute_type AS ENUM ('UNAUTHORIZED', 'DUPLICATE', 'GOODS_NOT_RECEIVED', 'NOT_AS_DESCRIBED', 'CREDIT_NOT_PROCESSED', 'TECHNICAL_ERROR', 'OTHER');
CREATE TYPE dispute_status AS ENUM ('OPEN', 'UNDER_REVIEW', 'PENDING_CUSTOMER', 'PENDING_MERCHANT', 'RESOLVED_CUSTOMER', 'RESOLVED_MERCHANT', 'WITHDRAWN', 'ESCALATED');
CREATE TYPE dispute_resolution AS ENUM ('CHARGEBACK', 'REPRESENTMENT', 'ARBITRATION', 'WITHDRAWN', 'PENDING');

CREATE TABLE disputes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference           VARCHAR(32) UNIQUE NOT NULL,
    transaction_id      UUID NOT NULL,
    account_id          UUID NOT NULL,
    party_id            UUID NOT NULL,
    dispute_type        dispute_type NOT NULL,
    status              dispute_status NOT NULL DEFAULT 'OPEN',
    resolution          dispute_resolution NOT NULL DEFAULT 'PENDING',
    amount              NUMERIC(20,4) NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'EUR',
    description         TEXT,
    merchant_name       VARCHAR(256),
    merchant_id         VARCHAR(64),
    transaction_date    DATE NOT NULL,
    filing_date         DATE NOT NULL DEFAULT CURRENT_DATE,
    resolution_deadline DATE,
    resolved_at         TIMESTAMPTZ,
    resolved_by         VARCHAR(64),
    chargeback_amount   NUMERIC(20,4),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE dispute_evidence (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dispute_id      UUID NOT NULL REFERENCES disputes(id),
    submitted_by    VARCHAR(64) NOT NULL,
    evidence_type   VARCHAR(64) NOT NULL,
    description     TEXT,
    file_reference  VARCHAR(256),
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE dispute_timeline (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dispute_id  UUID NOT NULL REFERENCES disputes(id),
    event_type  VARCHAR(64) NOT NULL,
    description TEXT NOT NULL,
    actor       VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_disputes_account ON disputes(account_id);
CREATE INDEX idx_disputes_transaction ON disputes(transaction_id);
CREATE INDEX idx_disputes_status ON disputes(status);
CREATE INDEX idx_disputes_filing_date ON disputes(filing_date);
CREATE INDEX idx_evidence_dispute ON dispute_evidence(dispute_id);
CREATE INDEX idx_timeline_dispute ON dispute_timeline(dispute_id);
