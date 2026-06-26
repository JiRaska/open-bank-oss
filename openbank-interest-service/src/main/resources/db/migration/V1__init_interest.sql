CREATE TYPE interest_rate_type AS ENUM ('FIXED', 'VARIABLE', 'TIERED');
CREATE TYPE accrual_status AS ENUM ('ACCRUING', 'CAPITALIZED', 'REVERSED', 'SUSPENDED');
CREATE TYPE day_count AS ENUM ('ACT_365', 'ACT_360', 'ACT_ACT', '30_360');

CREATE TABLE interest_rate_configs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id      VARCHAR(64) NOT NULL,
    rate_type       interest_rate_type NOT NULL DEFAULT 'FIXED',
    annual_rate     NUMERIC(10,6) NOT NULL,
    min_balance     NUMERIC(20,4) NOT NULL DEFAULT 0,
    max_balance     NUMERIC(20,4),
    day_count       day_count NOT NULL DEFAULT 'ACT_365',
    effective_from  DATE NOT NULL,
    effective_to    DATE,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE interest_accruals (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL,
    product_id      VARCHAR(64) NOT NULL,
    config_id       UUID NOT NULL REFERENCES interest_rate_configs(id),
    accrual_date    DATE NOT NULL,
    balance         NUMERIC(20,4) NOT NULL,
    daily_rate      NUMERIC(14,10) NOT NULL,
    accrued_amount  NUMERIC(20,6) NOT NULL,
    currency        CHAR(3) NOT NULL DEFAULT 'EUR',
    status          accrual_status NOT NULL DEFAULT 'ACCRUING',
    capitalized_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(account_id, accrual_date, product_id)
);

CREATE TABLE interest_capitalizations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL,
    product_id      VARCHAR(64) NOT NULL,
    period_from     DATE NOT NULL,
    period_to       DATE NOT NULL,
    total_accrued   NUMERIC(20,6) NOT NULL,
    capitalized_amount NUMERIC(20,4) NOT NULL,
    currency        CHAR(3) NOT NULL DEFAULT 'EUR',
    ledger_entry_id UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accruals_account ON interest_accruals(account_id);
CREATE INDEX idx_accruals_date ON interest_accruals(accrual_date);
CREATE INDEX idx_accruals_status ON interest_accruals(status);
CREATE INDEX idx_capitalizations_account ON interest_capitalizations(account_id);
CREATE INDEX idx_rate_configs_product ON interest_rate_configs(product_id);
