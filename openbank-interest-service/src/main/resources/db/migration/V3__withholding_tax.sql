-- ADR-0033: withholding tax on credit interest at capitalization (§36/§38d ZDP).
-- Adds the net/gross/tax split to capitalizations and a paired withholding-tax liability ledger.
--
-- Rollback note:
--   DROP TABLE withholding_tax;
--   ALTER TABLE interest_capitalizations
--       DROP COLUMN gross_amount, DROP COLUMN tax_amount, DROP COLUMN net_amount;
--   DROP TYPE withholding_treatment;
--   DROP TYPE withholding_tax_status;
-- (No data backfill is irreversible: existing rows were withholding-free, so net = gross = capitalized.)

CREATE TYPE withholding_treatment AS ENUM ('WITHHELD', 'NOT_WITHHELD', 'EXEMPT', 'DEFERRED_FX');
CREATE TYPE withholding_tax_status AS ENUM ('RECORDED', 'REMITTED', 'RECONCILED', 'REVERSED');

-- Enrich capitalizations with the gross/tax/net split. Existing rows were credited gross with no
-- withholding, so backfill net = gross = the prior capitalized_amount and tax = 0.
ALTER TABLE interest_capitalizations
    ADD COLUMN gross_amount NUMERIC(20,4) NOT NULL DEFAULT 0,
    ADD COLUMN tax_amount   NUMERIC(20,4) NOT NULL DEFAULT 0,
    ADD COLUMN net_amount   NUMERIC(20,4) NOT NULL DEFAULT 0;

UPDATE interest_capitalizations
    SET gross_amount = capitalized_amount,
        net_amount   = capitalized_amount
    WHERE gross_amount = 0;

CREATE TABLE withholding_tax (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    capitalization_id  UUID NOT NULL REFERENCES interest_capitalizations(id),
    account_id         UUID NOT NULL,
    party_ref          VARCHAR(128),
    period_from        DATE NOT NULL,
    period_to          DATE NOT NULL,
    taxable_base       NUMERIC(20,4) NOT NULL,
    rate               NUMERIC(6,4) NOT NULL,
    tax_amount         NUMERIC(20,4) NOT NULL,
    currency           CHAR(3) NOT NULL,
    treatment          withholding_treatment NOT NULL,
    exempt_code        VARCHAR(64),
    status             withholding_tax_status NOT NULL DEFAULT 'RECORDED',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_withholding_tax_account ON withholding_tax(account_id);
CREATE INDEX idx_withholding_tax_capitalization ON withholding_tax(capitalization_id);
CREATE INDEX idx_withholding_tax_status ON withholding_tax(status);
