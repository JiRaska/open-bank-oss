-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 D2/D4: deals become typed instruments with legs, a header of the functional-baseline
-- catalogue, a kind and a terms version; their schedules are stored as a versioned, overridable
-- cash-flow ledger. Legs and the header are versioned JSON documents (schema version alongside);
-- cash flows are relational rows with explicit columns.
--
-- Existing deals: kind ACTUAL, version 1, schedule version 0 (no ledger until next generated),
-- legs NULL (derived from the flat fields on read, exactly as the legacy products always behaved).
--
-- Rollback (fresh or unused only — the new tables reference deals):
--   DROP TABLE deal_filters, position_closes, cash_flow_conflicts, cash_flow_overrides,
--              deal_cash_flows, deal_versions;
--   DROP SEQUENCE deal_cash_flows_seq, cash_flow_overrides_seq, cash_flow_conflicts_seq,
--                 deal_versions_seq, position_closes_seq, deal_filters_seq;
--   ALTER TABLE deals DROP COLUMN kind, DROP COLUMN book_id, DROP COLUMN legs_json,
--     DROP COLUMN leg_schema_version, DROP COLUMN header_json, DROP COLUMN header_schema_version,
--     DROP COLUMN deal_version, DROP COLUMN schedule_version, DROP COLUMN schedule_hash,
--     DROP COLUMN security_isin, DROP COLUMN last_changed_by;
--   and restore deals_product_check from V9 (only while no new-product row exists).

ALTER TABLE deals
    ADD COLUMN kind                   VARCHAR(16)  NOT NULL DEFAULT 'ACTUAL'
        CHECK (kind IN ('ACTUAL', 'PLANNED', 'HYPOTHETICAL')),
    ADD COLUMN book_id                VARCHAR(64),
    ADD COLUMN legs_json              TEXT,
    ADD COLUMN leg_schema_version     INT,
    ADD COLUMN header_json            TEXT,
    ADD COLUMN header_schema_version  INT,
    ADD COLUMN deal_version           INT          NOT NULL DEFAULT 1 CHECK (deal_version >= 1),
    ADD COLUMN schedule_version       INT          NOT NULL DEFAULT 0 CHECK (schedule_version >= 0),
    ADD COLUMN schedule_hash          VARCHAR(64),
    ADD COLUMN security_isin          VARCHAR(12),
    ADD COLUMN last_changed_by        VARCHAR(255);

ALTER TABLE deals DROP CONSTRAINT IF EXISTS deals_product_check;
ALTER TABLE deals ADD CONSTRAINT deals_product_check CHECK (product IN (
    'MM_PLACEMENT', 'MM_BORROWING', 'CNB_DEPOSIT_FACILITY', 'CNB_LOMBARD', 'FX_SPOT',
    'CASH', 'TREASURY_BILL', 'BOND', 'BILL_OF_EXCHANGE', 'LOAN', 'FX_FORWARD', 'FX_SWAP', 'FRA',
    'IRS', 'REPO', 'REVERSE_REPO'));

CREATE INDEX idx_deals_book ON deals (book_id);

CREATE SEQUENCE IF NOT EXISTS deal_versions_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE deal_versions (
    id                BIGINT       PRIMARY KEY,
    deal_id           UUID         NOT NULL REFERENCES deals (deal_id),
    version           INT          NOT NULL,
    terms_json        TEXT         NOT NULL,
    schedule_version  INT,
    schedule_hash     VARCHAR(64),
    created_by        VARCHAR(255) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    changes           TEXT,
    CONSTRAINT deal_versions_unique UNIQUE (deal_id, version)
);

CREATE SEQUENCE IF NOT EXISTS deal_cash_flows_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE deal_cash_flows (
    id                     BIGINT        PRIMARY KEY,
    flow_id                UUID          NOT NULL UNIQUE,
    deal_id                UUID          NOT NULL REFERENCES deals (deal_id),
    schedule_version       INT           NOT NULL CHECK (schedule_version >= 1),
    leg_index              INT           NOT NULL CHECK (leg_index >= 0),
    period_index           INT           NOT NULL CHECK (period_index >= -1),
    kind                   VARCHAR(20)   NOT NULL
        CHECK (kind IN ('PRINCIPAL', 'INTEREST', 'FEE', 'NOTIONAL_EXCHANGE', 'PREMIUM')),
    direction              VARCHAR(3)    NOT NULL CHECK (direction IN ('IN', 'OUT')),
    currency               CHAR(3)       NOT NULL,
    accrual_start          DATE,
    accrual_end            DATE,
    fixing_date            DATE,
    payment_date           DATE          NOT NULL,
    notional               NUMERIC(20,2),
    rate                   NUMERIC(12,8),
    rate_source            VARCHAR(24)   NOT NULL
        CHECK (rate_source IN ('FIXED', 'OBSERVED', 'PROJECTED', 'RATE_UNTIL_FIRST_FIXING', 'MISSING')),
    amount                 NUMERIC(20,2) CHECK (amount IS NULL OR amount >= 0),
    state                  VARCHAR(12)   NOT NULL
        CHECK (state IN ('PROJECTED', 'FIXED', 'DUE', 'SETTLED', 'POSTED', 'CANCELLED')),
    leg_hash               VARCHAR(64)   NOT NULL,
    manual                 BOOLEAN       NOT NULL DEFAULT FALSE,
    original_payment_date  DATE,
    original_amount        NUMERIC(20,2),
    override_id            UUID,
    payment_id             VARCHAR(64),
    CONSTRAINT deal_cash_flows_slot UNIQUE (deal_id, schedule_version, leg_index, period_index, kind),
    CONSTRAINT deal_cash_flows_fixed_has_amount CHECK (state <> 'FIXED' OR amount IS NOT NULL),
    CONSTRAINT deal_cash_flows_manual CHECK (manual = (override_id IS NOT NULL))
);
CREATE INDEX idx_deal_cash_flows_deal ON deal_cash_flows (deal_id, schedule_version);
CREATE INDEX idx_deal_cash_flows_payment ON deal_cash_flows (payment_date);
CREATE INDEX idx_deal_cash_flows_fixing ON deal_cash_flows (fixing_date) WHERE fixing_date IS NOT NULL;

CREATE SEQUENCE IF NOT EXISTS cash_flow_overrides_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE cash_flow_overrides (
    id               BIGINT        PRIMARY KEY,
    override_id      UUID          NOT NULL UNIQUE,
    deal_id          UUID          NOT NULL REFERENCES deals (deal_id),
    flow_id          UUID          NOT NULL,
    leg_index        INT           NOT NULL,
    period_index     INT           NOT NULL,
    kind             VARCHAR(20)   NOT NULL,
    leg_hash         VARCHAR(64)   NOT NULL,
    payment_date     DATE,
    amount           NUMERIC(20,2) CHECK (amount IS NULL OR amount >= 0),
    reason           TEXT          NOT NULL,
    proposed_by      VARCHAR(255)  NOT NULL,
    proposed_by_type VARCHAR(20)   NOT NULL,
    proposed_at      TIMESTAMPTZ   NOT NULL,
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CONFLICT')),
    decided_by       VARCHAR(255),
    decided_by_type  VARCHAR(20),
    decided_at       TIMESTAMPTZ,
    decision_reason  TEXT,
    row_version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT cash_flow_overrides_change CHECK (payment_date IS NOT NULL OR amount IS NOT NULL),
    -- Four-eyes and human decision, again at the database.
    CONSTRAINT cash_flow_overrides_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT cash_flow_overrides_decider_human CHECK (decided_by_type IS NULL OR decided_by_type = 'HUMAN')
);
CREATE INDEX idx_cash_flow_overrides_deal ON cash_flow_overrides (deal_id, status);

CREATE SEQUENCE IF NOT EXISTS cash_flow_conflicts_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE cash_flow_conflicts (
    id                     BIGINT       PRIMARY KEY,
    conflict_id            UUID         NOT NULL UNIQUE,
    deal_id                UUID         NOT NULL REFERENCES deals (deal_id),
    override_id            UUID         NOT NULL,
    leg_index              INT          NOT NULL,
    period_index           INT          NOT NULL,
    kind                   VARCHAR(20)  NOT NULL,
    from_schedule_version  INT          NOT NULL,
    to_schedule_version    INT          NOT NULL,
    detected_at            TIMESTAMPTZ  NOT NULL,
    reason                 TEXT         NOT NULL
);
CREATE INDEX idx_cash_flow_conflicts_deal ON cash_flow_conflicts (deal_id);

CREATE SEQUENCE IF NOT EXISTS position_closes_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE position_closes (
    id             BIGINT       PRIMARY KEY,
    snapshot_id    UUID         NOT NULL UNIQUE,
    close_date     DATE         NOT NULL,
    book_id        VARCHAR(64),
    scenario       VARCHAR(64),
    special        BOOLEAN      NOT NULL,
    closed_by      VARCHAR(255) NOT NULL,
    closed_at      TIMESTAMPTZ  NOT NULL,
    positions_json TEXT         NOT NULL,
    command_key    VARCHAR(128) NOT NULL UNIQUE
);

CREATE SEQUENCE IF NOT EXISTS deal_filters_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE deal_filters (
    id          BIGINT       PRIMARY KEY,
    owner       VARCHAR(255) NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    filter_json TEXT         NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT deal_filters_owner_name UNIQUE (owner, name)
);

-- ADR-0344 functional baseline catalogue: the full list of reference-data kinds.
ALTER TABLE reference_records DROP CONSTRAINT IF EXISTS reference_records_kind_check;
ALTER TABLE reference_records ADD CONSTRAINT reference_records_kind_check CHECK (kind IN (
    'calendar', 'index', 'counterparty', 'ssi', 'netting', 'security', 'book', 'bucket-set',
    'regulatory-code', 'product-type', 'party-group', 'party-type', 'country', 'document-type',
    'account', 'settlement-place', 'loan-type', 'communication-channel', 'market-factor',
    'own-capital-item', 'period-length', 'scenario', 'behavioural-scenario', 'calculation-settings',
    'limit-settings', 'derivative-conversion-factor', 'collateral-haircut', 'portfolio-group',
    'accounting-settings', 'balance-sheet-side', 'dealing-filter'));

-- An override command records the override it produced (`OVERRIDE_APPROVE:<uuid>`), so a replay
-- answers with that override.
ALTER TABLE deal_commands ALTER COLUMN action TYPE VARCHAR(80);
