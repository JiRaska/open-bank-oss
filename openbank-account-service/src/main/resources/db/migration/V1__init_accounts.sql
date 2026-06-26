-- OpenBank Account Service — Initial Schema
-- V1__init_accounts.sql

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE accounts (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    account_number  VARCHAR(34) NOT NULL,
    account_type    VARCHAR(20) NOT NULL,
    party_id        UUID        NOT NULL,
    product_id      UUID        NOT NULL,
    currency_code   CHAR(3)     NOT NULL,
    status          VARCHAR(25) NOT NULL DEFAULT 'ACTIVE',
    opened_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT uq_accounts_account_number UNIQUE (account_number),
    CONSTRAINT chk_accounts_type CHECK (account_type IN (
        'CURRENT','SAVINGS','NOSTRO','GL_ASSET','GL_LIABILITY','GL_INCOME','GL_EXPENSE'
    )),
    CONSTRAINT chk_accounts_status CHECK (status IN (
        'PENDING_ACTIVATION','ACTIVE','DORMANT','FROZEN','CLOSED'
    )),
    CONSTRAINT chk_accounts_currency CHECK (char_length(currency_code) = 3)
);

CREATE TABLE account_balances (
    account_id          UUID            NOT NULL,
    available_balance   NUMERIC(20,6)   NOT NULL DEFAULT 0,
    current_balance     NUMERIC(20,6)   NOT NULL DEFAULT 0,
    reserved_balance    NUMERIC(20,6)   NOT NULL DEFAULT 0,
    pending_balance     NUMERIC(20,6)   NOT NULL DEFAULT 0,
    currency_code       CHAR(3)         NOT NULL,
    last_updated_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version             BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_account_balances PRIMARY KEY (account_id),
    CONSTRAINT fk_account_balances_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT chk_reserved_non_negative CHECK (reserved_balance >= 0),
    CONSTRAINT chk_pending_non_negative CHECK (pending_balance >= 0)
);

CREATE INDEX idx_accounts_party_id     ON accounts(party_id);
CREATE INDEX idx_accounts_status       ON accounts(status);
CREATE INDEX idx_accounts_currency     ON accounts(currency_code);
CREATE INDEX idx_accounts_opened_at    ON accounts(opened_at DESC);

CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
