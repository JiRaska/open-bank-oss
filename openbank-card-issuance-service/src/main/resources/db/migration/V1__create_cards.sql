CREATE TABLE IF NOT EXISTS cards (
    id                        UUID PRIMARY KEY,
    idempotency_key           VARCHAR(255) NOT NULL UNIQUE,
    party_id                  UUID NOT NULL,
    account_id                UUID NOT NULL,
    product_code              VARCHAR(50) NOT NULL,
    card_type                 VARCHAR(20) NOT NULL,
    network                   VARCHAR(20) NOT NULL,
    masked_pan                VARCHAR(25) NOT NULL,
    cardholder_name           VARCHAR(100) NOT NULL,
    embossed_name             VARCHAR(26) NOT NULL,
    expiry_date               DATE NOT NULL,
    status                    VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    daily_limit_minor_units   BIGINT NOT NULL DEFAULT 500000,
    monthly_limit_minor_units BIGINT NOT NULL DEFAULT 5000000,
    currency                  CHAR(3) NOT NULL DEFAULT 'CZK',
    delivery_address          TEXT,
    activated_at              TIMESTAMPTZ,
    blocked_at                TIMESTAMPTZ,
    blocked_reason            TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cards_party_id   ON cards(party_id);
CREATE INDEX idx_cards_account_id ON cards(account_id);
CREATE INDEX idx_cards_status     ON cards(status);
CREATE INDEX idx_cards_network    ON cards(network);

COMMENT ON TABLE cards IS 'PCI DSS compliant card records — PAN stored masked only';
