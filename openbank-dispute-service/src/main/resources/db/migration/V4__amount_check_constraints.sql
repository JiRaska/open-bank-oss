-- Disputed and chargeback amounts must be positive when present.
ALTER TABLE disputes
    ADD CONSTRAINT chk_dispute_amount_positive     CHECK (amount > 0),
    ADD CONSTRAINT chk_chargeback_amount_positive  CHECK (chargeback_amount IS NULL OR chargeback_amount > 0);
