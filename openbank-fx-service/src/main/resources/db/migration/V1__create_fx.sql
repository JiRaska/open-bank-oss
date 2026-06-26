CREATE TABLE IF NOT EXISTS fx_rates (
    id             UUID PRIMARY KEY,
    base_currency  CHAR(3) NOT NULL,
    quote_currency CHAR(3) NOT NULL,
    bid_rate       NUMERIC(18,8) NOT NULL,
    ask_rate       NUMERIC(18,8) NOT NULL,
    rate_type      VARCHAR(20) NOT NULL DEFAULT 'SPOT',
    source         VARCHAR(20) NOT NULL DEFAULT 'ECB',
    valid_from     TIMESTAMPTZ NOT NULL,
    valid_to       TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_fx_rates_pair ON fx_rates(base_currency, quote_currency, rate_type, valid_to);

CREATE TABLE IF NOT EXISTS fx_conversions (
    id                      UUID PRIMARY KEY,
    idempotency_key         VARCHAR(255) NOT NULL UNIQUE,
    party_id                UUID NOT NULL,
    account_id              UUID,
    from_currency           CHAR(3) NOT NULL,
    to_currency             CHAR(3) NOT NULL,
    from_amount_minor_units BIGINT NOT NULL,
    to_amount_minor_units   BIGINT NOT NULL,
    applied_rate            NUMERIC(18,8) NOT NULL,
    fee_minor_units         BIGINT NOT NULL DEFAULT 0,
    rate_id                 UUID NOT NULL REFERENCES fx_rates(id),
    status                  VARCHAR(20) NOT NULL DEFAULT 'SETTLED',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at              TIMESTAMPTZ
);
CREATE INDEX idx_fx_conv_party ON fx_conversions(party_id);

-- Seed ECB reference rates (CZK pairs)
INSERT INTO fx_rates VALUES
    (gen_random_uuid(),'EUR','CZK',24.85,25.15,'SPOT','ECB',NOW(),NOW()+INTERVAL '1 day',NOW()),
    (gen_random_uuid(),'USD','CZK',22.90,23.20,'SPOT','ECB',NOW(),NOW()+INTERVAL '1 day',NOW()),
    (gen_random_uuid(),'GBP','CZK',29.10,29.50,'SPOT','ECB',NOW(),NOW()+INTERVAL '1 day',NOW()),
    (gen_random_uuid(),'CHF','CZK',25.60,25.95,'SPOT','ECB',NOW(),NOW()+INTERVAL '1 day',NOW()),
    (gen_random_uuid(),'CZK','EUR',0.0392,0.0398,'SPOT','ECB',NOW(),NOW()+INTERVAL '1 day',NOW()),
    (gen_random_uuid(),'CZK','USD',0.0425,0.0432,'SPOT','ECB',NOW(),NOW()+INTERVAL '1 day',NOW());
