-- ADR-0334 slice S4: segregated pension funds, strategies, NAV and the unit register.
-- These tables hold PARTICIPANTS' assets and units, never bank positions: nothing here is posted
-- into treasury-service or the bank ledger.
-- Rollback:
--   DROP TABLE unit_transactions; DROP TABLE unit_holdings; DROP TABLE unit_orders;
--   DROP TABLE fund_navs; DROP TABLE strategy_changes; DROP TABLE fund_strategies; DROP TABLE funds;

CREATE TABLE funds (
    id                         UUID PRIMARY KEY,
    name                       VARCHAR(256) NOT NULL,
    isin                       CHAR(12) NOT NULL UNIQUE,
    lei                        CHAR(20) NOT NULL,
    depositary_reference       VARCHAR(128) NOT NULL,
    custody_account_reference  VARCHAR(128) NOT NULL,
    currency                   CHAR(3) NOT NULL,
    risk_class                 SMALLINT NOT NULL CHECK (risk_class BETWEEN 1 AND 7),
    mandatory_conservative     BOOLEAN NOT NULL,
    management_fee_rate        NUMERIC(9, 6) NOT NULL,
    launch_nav_per_unit        NUMERIC(19, 6) NOT NULL,
    status                     VARCHAR(16) NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL
);

CREATE TABLE fund_strategies (
    id           UUID PRIMARY KEY,
    name         VARCHAR(256) NOT NULL,
    allocations  TEXT NOT NULL,
    glide_path   TEXT NOT NULL DEFAULT '[]',
    status       VARCHAR(16) NOT NULL,
    version      INTEGER NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE strategy_changes (
    id                             UUID PRIMARY KEY,
    strategy_id                    UUID NOT NULL REFERENCES fund_strategies (id),
    proposed_allocations           TEXT NOT NULL,
    proposed_glide_path            TEXT NOT NULL DEFAULT '[]',
    reason                         VARCHAR(1024) NOT NULL,
    effective_date                 DATE NOT NULL,
    submitted_by                   VARCHAR(256) NOT NULL,
    submitted_at                   TIMESTAMPTZ NOT NULL,
    status                         VARCHAR(24) NOT NULL,
    decided_by                     VARCHAR(256),
    decided_at                     TIMESTAMPTZ,
    participant_notification_date  DATE,
    applied_at                     TIMESTAMPTZ,
    -- Four-eyes, stated again where a writer that bypasses the aggregate cannot avoid it.
    CONSTRAINT ck_strategy_change_four_eyes CHECK (decided_by IS NULL OR decided_by <> submitted_by)
);

CREATE INDEX idx_strategy_changes_strategy ON strategy_changes (strategy_id);

CREATE TABLE fund_navs (
    id                      UUID PRIMARY KEY,
    fund_id                 UUID NOT NULL REFERENCES funds (id),
    valuation_date          DATE NOT NULL,
    gross_assets            NUMERIC(21, 2) NOT NULL,
    accrued_management_fee  NUMERIC(21, 2) NOT NULL,
    other_liabilities       NUMERIC(21, 2) NOT NULL,
    net_assets              NUMERIC(21, 2) NOT NULL,
    units_outstanding       NUMERIC(25, 6) NOT NULL,
    nav_per_unit            NUMERIC(19, 6) NOT NULL,
    status                  VARCHAR(16) NOT NULL,
    calculated_by           VARCHAR(256) NOT NULL,
    calculated_at           TIMESTAMPTZ NOT NULL,
    corrects_nav_id         UUID REFERENCES fund_navs (id),
    approved_by             VARCHAR(256),
    published_at            TIMESTAMPTZ,
    CONSTRAINT ck_fund_nav_four_eyes CHECK (approved_by IS NULL OR approved_by <> calculated_by)
);

-- At most one PUBLISHED NAV per fund and day: a correction supersedes, it never coexists.
CREATE UNIQUE INDEX uq_fund_navs_published ON fund_navs (fund_id, valuation_date) WHERE status = 'PUBLISHED';
CREATE INDEX idx_fund_navs_fund ON fund_navs (fund_id, valuation_date);

CREATE TABLE unit_orders (
    id               UUID PRIMARY KEY,
    contract_id      UUID NOT NULL,
    fund_id          UUID NOT NULL REFERENCES funds (id),
    order_type       VARCHAR(16) NOT NULL,
    amount           NUMERIC(21, 2),
    units            NUMERIC(25, 6),
    target_fund_id   UUID REFERENCES funds (id),
    parent_order_id  UUID REFERENCES unit_orders (id),
    status           VARCHAR(16) NOT NULL,
    placed_at        TIMESTAMPTZ NOT NULL,
    settled_at       TIMESTAMPTZ,
    nav_id           UUID REFERENCES fund_navs (id),
    -- Caller-supplied Idempotency-Key (NULL only for a SWITCH_IN leg the service creates itself):
    -- a retried placement returns the original order instead of queueing money twice.
    idempotency_key  VARCHAR(128),
    CONSTRAINT uq_unit_orders_idempotency UNIQUE (contract_id, idempotency_key)
);

CREATE INDEX idx_unit_orders_pending ON unit_orders (fund_id, placed_at) WHERE status = 'PENDING';
CREATE INDEX idx_unit_orders_contract ON unit_orders (contract_id);

CREATE TABLE unit_holdings (
    id           UUID PRIMARY KEY,
    contract_id  UUID NOT NULL,
    fund_id      UUID NOT NULL REFERENCES funds (id),
    units        NUMERIC(25, 6) NOT NULL CHECK (units >= 0),
    -- Optimistic lock: two settlements racing on one holding cannot both write from the same read.
    version      BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_unit_holdings_contract_fund UNIQUE (contract_id, fund_id)
);

CREATE TABLE unit_transactions (
    id                     UUID PRIMARY KEY,
    order_id               UUID REFERENCES unit_orders (id),
    contract_id            UUID NOT NULL,
    fund_id                UUID NOT NULL REFERENCES funds (id),
    transaction_type       VARCHAR(16) NOT NULL,
    units                  NUMERIC(25, 6) NOT NULL,
    amount                 NUMERIC(21, 2) NOT NULL,
    nav_id                 UUID NOT NULL REFERENCES fund_navs (id),
    nav_per_unit           NUMERIC(19, 6) NOT NULL,
    priced_at              TIMESTAMPTZ NOT NULL,
    corrected_from_nav_id  UUID REFERENCES fund_navs (id)
);

CREATE INDEX idx_unit_transactions_contract ON unit_transactions (contract_id);
CREATE INDEX idx_unit_transactions_nav ON unit_transactions (nav_id);
