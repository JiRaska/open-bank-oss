-- ADR-0103: capture HOW money moved as first-class facts. Two orthogonal dimensions
-- (rail + instruction type) plus an optional MCC-derived merchant category. D1 = additive
-- nullable columns only; originating services begin stamping them in D2, so existing rows
-- and current write paths are unaffected (values stay NULL = UNKNOWN until backfilled).
ALTER TABLE transactions ADD COLUMN rail VARCHAR(16);
ALTER TABLE transactions ADD COLUMN instruction_type VARCHAR(24);
ALTER TABLE transactions ADD COLUMN merchant_category VARCHAR(32);
