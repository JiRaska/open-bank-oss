-- ADR-0314 D5: a loan says whether its rate is fixed or floats, and if it floats, over which index,
-- at what spread, how often it resets and when next. Every existing row is FIXED, which is true:
-- no loan here has ever repriced. Nullable floating terms, constrained to appear only together.
-- Rollback: ALTER TABLE loan_application / loan DROP COLUMN rate_type, rate_index, spread,
--           reset_frequency_months, next_reset_date;
ALTER TABLE loan_application
    ADD COLUMN rate_type VARCHAR(8) NOT NULL DEFAULT 'FIXED',
    ADD COLUMN rate_index VARCHAR(16),
    ADD COLUMN spread NUMERIC(10, 6),
    ADD COLUMN reset_frequency_months INTEGER,
    ADD COLUMN next_reset_date DATE;
ALTER TABLE loan_application ADD CONSTRAINT ck_loan_application_rate_terms CHECK (
    (rate_type = 'FIXED' AND rate_index IS NULL AND spread IS NULL AND reset_frequency_months IS NULL AND next_reset_date IS NULL)
    OR (rate_type = 'FLOATING' AND rate_index IS NOT NULL AND spread IS NOT NULL
        AND reset_frequency_months IN (1, 3, 6, 12) AND next_reset_date IS NOT NULL));

ALTER TABLE loan
    ADD COLUMN rate_type VARCHAR(8) NOT NULL DEFAULT 'FIXED',
    ADD COLUMN rate_index VARCHAR(16),
    ADD COLUMN spread NUMERIC(10, 6),
    ADD COLUMN reset_frequency_months INTEGER,
    ADD COLUMN next_reset_date DATE;
ALTER TABLE loan ADD CONSTRAINT ck_loan_rate_terms CHECK (
    (rate_type = 'FIXED' AND rate_index IS NULL AND spread IS NULL AND reset_frequency_months IS NULL AND next_reset_date IS NULL)
    OR (rate_type = 'FLOATING' AND rate_index IS NOT NULL AND spread IS NOT NULL
        AND reset_frequency_months IN (1, 3, 6, 12) AND next_reset_date IS NOT NULL));
