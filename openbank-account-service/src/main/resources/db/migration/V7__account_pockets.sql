-- Currency pockets (měnové složky) for single-IBAN multi-currency accounts (ADR-0024).
-- One account has one primary pocket plus N secondary pockets, each settling in its own currency.
-- The IBAN does not encode currency; the inbound payment's currency selects the pocket. A pocket
-- maps 1:1 to a balance-service balance keyed by (account_id, currency).

CREATE TABLE account_pockets (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    account_id    UUID        NOT NULL,
    currency_code CHAR(3)     NOT NULL,
    is_primary    BOOLEAN     NOT NULL DEFAULT false,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    opened_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at     TIMESTAMPTZ,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_account_pockets PRIMARY KEY (id),
    CONSTRAINT fk_account_pockets_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT uq_account_pockets_acc_ccy UNIQUE (account_id, currency_code),
    CONSTRAINT chk_account_pockets_status CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    CONSTRAINT chk_account_pockets_currency CHECK (char_length(currency_code) = 3)
);

-- At most one primary pocket per account.
CREATE UNIQUE INDEX uq_account_pockets_one_primary ON account_pockets(account_id) WHERE is_primary;
CREATE INDEX idx_account_pockets_account ON account_pockets(account_id);

CREATE TRIGGER trg_account_pockets_updated_at
    BEFORE UPDATE ON account_pockets
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- Backfill: every existing customer account gets a primary pocket in its own currency, so the
-- single-IBAN model is consistent for accounts opened before pockets existed.
INSERT INTO account_pockets (account_id, currency_code, is_primary, status, opened_at)
SELECT id, currency_code, true, 'ACTIVE', opened_at
FROM accounts
WHERE account_type IN ('CURRENT', 'SAVINGS');
