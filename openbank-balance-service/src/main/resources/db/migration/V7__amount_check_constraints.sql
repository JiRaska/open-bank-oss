-- Hold amounts must be strictly positive; a zero-amount hold would incorrectly
-- reduce available balance without a real financial obligation.
ALTER TABLE balance_holds
    ADD CONSTRAINT chk_hold_amount_positive CHECK (amount > 0);

-- Overdraft limit is non-negative; a negative limit is a configuration error.
ALTER TABLE balances
    ADD CONSTRAINT chk_overdraft_limit_nonneg CHECK (arranged_overdraft_limit >= 0);
