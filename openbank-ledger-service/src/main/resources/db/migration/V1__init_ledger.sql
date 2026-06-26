CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SEQUENCE journal_entry_number_seq START 1 INCREMENT 1 NO CYCLE;

CREATE TABLE gl_accounts (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    code        VARCHAR(20) NOT NULL,
    name        VARCHAR(200) NOT NULL,
    type        VARCHAR(20) NOT NULL,
    currency_code CHAR(3)   NOT NULL,
    parent_id   UUID,
    is_leaf     BOOLEAN     NOT NULL DEFAULT true,
    is_enabled  BOOLEAN     NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_gl_accounts PRIMARY KEY (id),
    CONSTRAINT uq_gl_accounts_code UNIQUE (code),
    CONSTRAINT chk_gl_accounts_type CHECK (type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    CONSTRAINT fk_gl_accounts_parent FOREIGN KEY (parent_id) REFERENCES gl_accounts(id)
);

CREATE TABLE journal_entries (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    entry_number    BIGINT      NOT NULL DEFAULT nextval('journal_entry_number_seq'),
    transaction_id  UUID        NOT NULL,
    entry_date      DATE        NOT NULL,
    value_date      DATE        NOT NULL,
    description     VARCHAR(500),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID        NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_journal_entries PRIMARY KEY (id, entry_date),
    CONSTRAINT uq_journal_entry_number UNIQUE (entry_number, entry_date),
    CONSTRAINT chk_journal_status CHECK (status IN ('PENDING','POSTED','REVERSED'))
) PARTITION BY RANGE (entry_date);

CREATE TABLE journal_entries_2024 PARTITION OF journal_entries
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE journal_entries_2025 PARTITION OF journal_entries
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE journal_entries_2026 PARTITION OF journal_entries
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE journal_entries_default PARTITION OF journal_entries DEFAULT;

CREATE TABLE journal_lines (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    journal_id      UUID            NOT NULL,
    gl_account_id   UUID            NOT NULL,
    side            CHAR(1)         NOT NULL,
    amount          NUMERIC(20,6)   NOT NULL,
    currency_code   CHAR(3)         NOT NULL,
    fx_rate         NUMERIC(20,10),
    base_amount     NUMERIC(20,6)   NOT NULL,
    base_currency   CHAR(3)         NOT NULL,
    sequence        INT             NOT NULL,

    CONSTRAINT pk_journal_lines PRIMARY KEY (id),
    CONSTRAINT chk_journal_lines_side CHECK (side IN ('D','C')),
    CONSTRAINT chk_journal_lines_amount CHECK (amount > 0),
    CONSTRAINT fk_journal_lines_gl FOREIGN KEY (gl_account_id) REFERENCES gl_accounts(id)
);

CREATE INDEX idx_journal_entries_transaction_id ON journal_entries(transaction_id);
CREATE INDEX idx_journal_entries_entry_date     ON journal_entries(entry_date DESC);
CREATE INDEX idx_journal_entries_status         ON journal_entries(status);
CREATE INDEX idx_journal_lines_journal_id       ON journal_lines(journal_id);
CREATE INDEX idx_journal_lines_gl_account       ON journal_lines(gl_account_id);

INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    (gen_random_uuid(), '1000', 'Cash and Cash Equivalents', 'ASSET', 'CZK', false, true),
    (gen_random_uuid(), '1001', 'Nostro Accounts', 'ASSET', 'CZK', true, true),
    (gen_random_uuid(), '2000', 'Customer Deposits', 'LIABILITY', 'CZK', false, true),
    (gen_random_uuid(), '2001', 'Current Account Deposits', 'LIABILITY', 'CZK', true, true),
    (gen_random_uuid(), '2002', 'Savings Account Deposits', 'LIABILITY', 'CZK', true, true),
    (gen_random_uuid(), '3000', 'Interest Income', 'INCOME', 'CZK', true, true),
    (gen_random_uuid(), '4000', 'Interest Expense', 'EXPENSE', 'CZK', true, true),
    (gen_random_uuid(), '4001', 'Fee Income', 'INCOME', 'CZK', true, true);
