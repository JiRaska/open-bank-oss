CREATE TABLE parties (
    id          BIGSERIAL PRIMARY KEY,
    party_id    UUID        NOT NULL UNIQUE,
    party_type  VARCHAR(20) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING_KYC',
    legal_name  VARCHAR(255) NOT NULL,
    trading_name VARCHAR(255),
    date_of_birth VARCHAR(10),
    nationality  VARCHAR(3),
    tax_id       VARCHAR(50),
    registration_number VARCHAR(100),
    email        VARCHAR(255) NOT NULL UNIQUE,
    phone        VARCHAR(30),
    address_line1       VARCHAR(255),
    address_line2       VARCHAR(255),
    address_city        VARCHAR(100),
    address_postal_code VARCHAR(20),
    address_country_code CHAR(2),
    kyc_status   VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE party_documents (
    id              BIGSERIAL PRIMARY KEY,
    document_id     UUID        NOT NULL UNIQUE,
    party_id        UUID        NOT NULL REFERENCES parties(party_id),
    document_type   VARCHAR(30) NOT NULL,
    document_number VARCHAR(100) NOT NULL,
    issuing_country CHAR(2)     NOT NULL,
    expiry_date     VARCHAR(10),
    verified_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_parties_email ON parties(email);
CREATE INDEX idx_parties_status ON parties(status);
CREATE INDEX idx_party_documents_party_id ON party_documents(party_id);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
