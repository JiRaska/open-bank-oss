-- ADR-0314 D5: a VARIABLE rate can name the market index it follows and the spread over it, so the
-- risk engine can reprice a deposit when the index moves instead of assuming the rate is frozen.
-- Both nullable and additive: every existing row keeps its meaning (no index = rate set by hand).
-- Rollback: ALTER TABLE interest_rate_configs DROP COLUMN rate_index, DROP COLUMN spread;
ALTER TABLE interest_rate_configs ADD COLUMN rate_index VARCHAR(16);
ALTER TABLE interest_rate_configs ADD COLUMN spread NUMERIC(20, 18);
ALTER TABLE interest_rate_configs ADD CONSTRAINT ck_rate_index_only_variable
    CHECK ((rate_index IS NULL AND spread IS NULL) OR (rate_type = 'VARIABLE' AND rate_index IS NOT NULL AND spread IS NOT NULL));
