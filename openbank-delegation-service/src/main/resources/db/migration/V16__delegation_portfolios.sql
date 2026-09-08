-- Named corporate account portfolios. Additive only: older binaries ignore these tables.
-- Rollback: revert application images; if permanent removal is required, use a new forward Flyway
-- migration to drop both tables (never edit an applied migration checksum).
CREATE TABLE delegation_portfolios (
    id UUID PRIMARY KEY,
    owner_party_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE delegation_portfolio_accounts (
    portfolio_id UUID NOT NULL REFERENCES delegation_portfolios(id) ON DELETE CASCADE,
    account_id UUID NOT NULL,
    PRIMARY KEY (portfolio_id, account_id)
);

CREATE INDEX idx_delegation_portfolios_owner ON delegation_portfolios(owner_party_id, name);
GRANT ALL ON delegation_portfolios, delegation_portfolio_accounts TO openbank;
