-- Governance hardening: reversal linkage + idempotency ledger.

-- Link a reversal entry back to the journal it reverses (audit trail).
ALTER TABLE journal_entries ADD COLUMN reversal_of UUID;
CREATE INDEX idx_journal_entries_reversal_of ON journal_entries(reversal_of);

-- Idempotency store for journal posting. A given idempotency key maps to exactly
-- one journal entry; replays return the original entry instead of double-posting.
CREATE TABLE ledger_idempotency (
    idempotency_key     VARCHAR(255) NOT NULL,
    journal_id          UUID         NOT NULL,
    journal_entry_date  DATE         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_ledger_idempotency PRIMARY KEY (idempotency_key)
);

CREATE INDEX idx_ledger_idempotency_journal ON ledger_idempotency(journal_id);

-- Well-known posting (leaf) accounts with STABLE identifiers so that upstream
-- services (transaction saga, integration tests) can reference them deterministically.
INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000000001', '1100', 'Customer Cash Clearing',   'ASSET',     'CZK', true, true),
    ('a0000000-0000-0000-0000-000000000002', '2100', 'Customer Deposit Control', 'LIABILITY', 'CZK', true, true);
