-- AML case amount is nullable (SAR may have no transaction amount), but if present must be positive.
-- match_score is a percentage 0–100.
ALTER TABLE aml_cases
    ADD CONSTRAINT chk_aml_amount_positive    CHECK (amount IS NULL OR amount > 0),
    ADD CONSTRAINT chk_aml_match_score_range  CHECK (match_score IS NULL OR (match_score >= 0 AND match_score <= 100));
