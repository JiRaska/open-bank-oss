-- V3: Technical accounts, fee accounts, nostro/vostro, suspense
-- Required for BIAN: Current Account, Savings Account, Internal Account
-- Required for CNB: GL chart of accounts, fee management

-- Technical/GL account types
CREATE TABLE IF NOT EXISTS technical_accounts (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    account_number      VARCHAR(34)     NOT NULL,
    account_type        VARCHAR(30)     NOT NULL,
    gl_code             VARCHAR(20)     NOT NULL,
    name                VARCHAR(140)    NOT NULL,
    description         VARCHAR(500),
    currency            CHAR(3)         NOT NULL DEFAULT 'CZK',
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    balance             NUMERIC(20,6)   NOT NULL DEFAULT 0,
    normal_balance      VARCHAR(6)      NOT NULL DEFAULT 'DEBIT',
    parent_gl_code      VARCHAR(20),
    cost_center         VARCHAR(20),
    is_reconciliation   BOOLEAN         NOT NULL DEFAULT FALSE,
    is_suspense         BOOLEAN         NOT NULL DEFAULT FALSE,
    is_nostro           BOOLEAN         NOT NULL DEFAULT FALSE,
    is_vostro           BOOLEAN         NOT NULL DEFAULT FALSE,
    correspondent_bank_bic VARCHAR(11),
    correspondent_bank_name VARCHAR(140),
    opened_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    closed_at           TIMESTAMPTZ,
    last_movement_at    TIMESTAMPTZ,
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_technical_accounts PRIMARY KEY (id),
    CONSTRAINT uq_technical_accounts_number UNIQUE (account_number),
    CONSTRAINT uq_technical_accounts_gl_code UNIQUE (gl_code),
    CONSTRAINT chk_technical_accounts_type CHECK (account_type IN (
        'NOSTRO','VOSTRO','SUSPENSE','FEE_INCOME','FEE_EXPENSE',
        'INTEREST_INCOME','INTEREST_EXPENSE','PROVISION','CLEARING',
        'SETTLEMENT','CAPITAL','RESERVE','PROFIT_LOSS','TAX','OTHER'
    )),
    CONSTRAINT chk_technical_accounts_status CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    CONSTRAINT chk_technical_accounts_normal_balance CHECK (normal_balance IN ('DEBIT','CREDIT'))
);

-- Fee schedule
CREATE TABLE IF NOT EXISTS fee_schedules (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    fee_code            VARCHAR(30)     NOT NULL,
    name                VARCHAR(140)    NOT NULL,
    description         VARCHAR(500),
    fee_type            VARCHAR(20)     NOT NULL,
    calculation_method  VARCHAR(20)     NOT NULL,
    amount              NUMERIC(20,6),
    percentage          NUMERIC(8,6),
    min_amount          NUMERIC(20,6),
    max_amount          NUMERIC(20,6),
    currency            CHAR(3)         NOT NULL DEFAULT 'CZK',
    product_codes       TEXT[],
    transaction_types   TEXT[],
    channel_codes       TEXT[],
    income_account_gl   VARCHAR(20)     NOT NULL,
    effective_from      DATE            NOT NULL,
    effective_to        DATE,
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    vat_rate            NUMERIC(5,4)    NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_fee_schedules PRIMARY KEY (id),
    CONSTRAINT uq_fee_schedules_code UNIQUE (fee_code),
    CONSTRAINT chk_fee_type CHECK (fee_type IN (
        'ACCOUNT_MAINTENANCE','TRANSACTION','CARD','OVERDRAFT',
        'WIRE_TRANSFER','SEPA','CASH','STATEMENT','OTHER'
    )),
    CONSTRAINT chk_calculation_method CHECK (calculation_method IN (
        'FLAT','PERCENTAGE','TIERED','MIN_OF','MAX_OF','COMBINED'
    ))
);

-- BBAN format for Czech accounts (add to accounts table)
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS bban                VARCHAR(22),
    ADD COLUMN IF NOT EXISTS product_code        VARCHAR(30),
    ADD COLUMN IF NOT EXISTS interest_rate       NUMERIC(8,6),
    ADD COLUMN IF NOT EXISTS overdraft_limit     NUMERIC(20,6),
    ADD COLUMN IF NOT EXISTS overdraft_currency  CHAR(3),
    ADD COLUMN IF NOT EXISTS fee_schedule_code   VARCHAR(30),
    ADD COLUMN IF NOT EXISTS branch_code         VARCHAR(10),
    ADD COLUMN IF NOT EXISTS officer_id          UUID,
    ADD COLUMN IF NOT EXISTS last_statement_date DATE,
    ADD COLUMN IF NOT EXISTS next_statement_date DATE,
    ADD COLUMN IF NOT EXISTS statement_frequency VARCHAR(20) DEFAULT 'MONTHLY',
    ADD COLUMN IF NOT EXISTS e_statement_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sweep_account_id    UUID,
    ADD COLUMN IF NOT EXISTS credit_limit        NUMERIC(20,6),
    ADD COLUMN IF NOT EXISTS credit_limit_currency CHAR(3);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_technical_accounts_type   ON technical_accounts(account_type);
CREATE INDEX IF NOT EXISTS idx_technical_accounts_gl     ON technical_accounts(gl_code);
CREATE INDEX IF NOT EXISTS idx_technical_accounts_status ON technical_accounts(status);
CREATE INDEX IF NOT EXISTS idx_accounts_bban             ON accounts(bban) WHERE bban IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_accounts_product          ON accounts(product_code) WHERE product_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_fee_schedules_active      ON fee_schedules(is_active, effective_from, effective_to);

-- Seed standard Czech bank technical accounts
INSERT INTO technical_accounts (account_number, account_type, gl_code, name, currency, normal_balance, is_suspense, is_nostro) VALUES
    ('TECH-SUSPENSE-CZK',   'SUSPENSE',     '1001', 'Suspense Account CZK',         'CZK', 'DEBIT',  TRUE,  FALSE),
    ('TECH-SUSPENSE-EUR',   'SUSPENSE',     '1002', 'Suspense Account EUR',         'EUR', 'DEBIT',  TRUE,  FALSE),
    ('TECH-FEE-INCOME',     'FEE_INCOME',   '4001', 'Fee Income Account',           'CZK', 'CREDIT', FALSE, FALSE),
    ('TECH-INT-INCOME',     'INTEREST_INCOME','4101','Interest Income Account',     'CZK', 'CREDIT', FALSE, FALSE),
    ('TECH-INT-EXPENSE',    'INTEREST_EXPENSE','5101','Interest Expense Account',   'CZK', 'DEBIT',  FALSE, FALSE),
    ('TECH-CLEARING-CZK',   'CLEARING',     '1101', 'Clearing Account CZK',         'CZK', 'DEBIT',  FALSE, FALSE),
    ('TECH-CLEARING-EUR',   'CLEARING',     '1102', 'Clearing Account EUR',         'EUR', 'DEBIT',  FALSE, FALSE),
    ('TECH-SETTLEMENT',     'SETTLEMENT',   '1201', 'CNB Settlement Account',       'CZK', 'DEBIT',  FALSE, TRUE),
    ('TECH-PROVISION',      'PROVISION',    '5201', 'Loan Loss Provision',          'CZK', 'CREDIT', FALSE, FALSE),
    ('TECH-TAX-VAT',        'TAX',          '3401', 'VAT Payable Account',          'CZK', 'CREDIT', FALSE, FALSE)
ON CONFLICT (gl_code) DO NOTHING;

COMMENT ON TABLE technical_accounts IS 'BIAN: Internal Account - GL/technical accounts for bank operations';
COMMENT ON TABLE fee_schedules IS 'BIAN: Product Fee - fee schedule per product/transaction type';
