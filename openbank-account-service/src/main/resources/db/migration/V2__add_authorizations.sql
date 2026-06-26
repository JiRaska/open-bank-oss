ALTER TABLE accounts ADD COLUMN signing_rule VARCHAR(20) NOT NULL DEFAULT 'SINGLE';
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_signing_rule
    CHECK (signing_rule IN ('SINGLE','JOINT_ALL','JOINT_ANY_TWO','OWNER_PLUS_ONE'));

CREATE TABLE account_authorizations (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id                  UUID NOT NULL REFERENCES accounts(id),
    party_id                    UUID NOT NULL,
    role                        VARCHAR(20) NOT NULL,
    daily_limit_amount          NUMERIC(20,6),
    daily_limit_currency        CHAR(3),
    transaction_limit_amount    NUMERIC(20,6),
    transaction_limit_currency  CHAR(3),
    valid_from                  DATE NOT NULL,
    valid_to                    DATE,
    status                      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    granted_by                  UUID NOT NULL,
    granted_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_by                  UUID,
    revoked_at                  TIMESTAMPTZ,
    revoked_reason              TEXT,
    CONSTRAINT chk_auth_role CHECK (role IN ('FULL_ACCESS','PAYMENT_ONLY','READ_ONLY','CARD_HOLDER')),
    CONSTRAINT chk_auth_status CHECK (status IN ('ACTIVE','SUSPENDED','REVOKED','EXPIRED')),
    CONSTRAINT chk_auth_valid_range CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE INDEX idx_auth_account_id ON account_authorizations(account_id);
CREATE INDEX idx_auth_party_id ON account_authorizations(party_id);
CREATE INDEX idx_auth_account_party ON account_authorizations(account_id, party_id);
CREATE INDEX idx_auth_status ON account_authorizations(status);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
