CREATE TABLE balances (
    id               BIGSERIAL PRIMARY KEY,
    account_id       UUID NOT NULL,
    currency         CHAR(3) NOT NULL,
    booked_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,
    available_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    reserved_amount  NUMERIC(19,4) NOT NULL DEFAULT 0,
    pending_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version          BIGINT NOT NULL DEFAULT 0,
    UNIQUE (account_id, currency)
);

CREATE INDEX idx_balances_account_id ON balances(account_id);

CREATE TABLE balance_holds (
    id           BIGSERIAL PRIMARY KEY,
    hold_id      UUID NOT NULL UNIQUE,
    account_id   UUID NOT NULL,
    amount       NUMERIC(19,4) NOT NULL,
    currency     CHAR(3) NOT NULL,
    reason       VARCHAR(255) NOT NULL,
    reference_id VARCHAR(100) NOT NULL,
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at  TIMESTAMPTZ
);

CREATE INDEX idx_holds_account_id ON balance_holds(account_id);
CREATE INDEX idx_holds_reference_id ON balance_holds(reference_id);
