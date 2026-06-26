-- Payment amounts must be strictly positive; a zero-value clearing item or batch total
-- indicates a data error, not a legitimate business state.
ALTER TABLE clearing_items
    ADD CONSTRAINT chk_clearing_item_amount_positive CHECK (amount > 0);

ALTER TABLE clearing_batches
    ADD CONSTRAINT chk_clearing_batch_debit_nonneg  CHECK (total_debit  >= 0),
    ADD CONSTRAINT chk_clearing_batch_credit_nonneg CHECK (total_credit >= 0);
