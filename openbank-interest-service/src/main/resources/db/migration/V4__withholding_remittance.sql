-- ADR-0038: monthly withholding-tax remittance (Vyúčtování daně vybírané srážkou, §38d odst. 3 ZDP).
-- Adds the per-period remittance batch and links each remitted withholding row to its batch.
--
-- Rollback note:
--   ALTER TABLE withholding_tax DROP COLUMN remittance_id;
--   DROP TABLE withholding_remittance;
--   DROP TYPE withholding_remittance_status;
-- (Reversible: dropping remittance_id leaves the withholding rows intact; statuses already advanced
--  to REMITTED can be reset with UPDATE withholding_tax SET status = 'RECORDED' WHERE status = 'REMITTED'.)

CREATE TYPE withholding_remittance_status AS ENUM ('PENDING', 'SETTLED');

CREATE TABLE withholding_remittance (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    period_year        INT NOT NULL,
    period_month       INT NOT NULL,
    authority          VARCHAR(32) NOT NULL,
    currency           CHAR(3) NOT NULL,
    total_tax_amount   NUMERIC(20,4) NOT NULL,
    item_count         INT NOT NULL,
    due_date           DATE NOT NULL,
    status             withholding_remittance_status NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_withholding_remittance_period UNIQUE (period_year, period_month, authority)
);

-- Link each withholding row to the batch it was remitted in (NULL until REMITTED).
ALTER TABLE withholding_tax
    ADD COLUMN remittance_id UUID REFERENCES withholding_remittance(id);

CREATE INDEX idx_withholding_tax_remittance ON withholding_tax(remittance_id);
