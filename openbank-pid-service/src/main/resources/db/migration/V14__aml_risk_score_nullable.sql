-- ADR-0353 D2 — pid-service stops asserting a customer risk rating. `aml_risk_score` was
-- NOT NULL DEFAULT 'LOW', so every party (including every party registered from an EUDI Wallet
-- PID presentation) carried a LOW rating nothing had computed. The canonical rating is
-- kyc-service's `risk_level`; this column is deprecated.
--
-- Forward: new parties carry NULL. Existing rows keep their stored value (a LOW written by the
-- default cannot be told apart from a LOW a caller set via PUT /parties/{id}/kyc, so nothing is
-- rewritten). The CHECK constraint chk_aml_risk is unchanged: a NULL satisfies it.
--
-- Rollback (before apply only — never edit an applied migration):
--   UPDATE parties SET aml_risk_score = 'LOW' WHERE aml_risk_score IS NULL;
--   ALTER TABLE parties ALTER COLUMN aml_risk_score SET DEFAULT 'LOW';
--   ALTER TABLE parties ALTER COLUMN aml_risk_score SET NOT NULL;
-- The UPDATE re-asserts the very defaulted rating this migration removes; only run it to roll back
-- an application version that cannot read NULL.
ALTER TABLE parties ALTER COLUMN aml_risk_score DROP DEFAULT;
ALTER TABLE parties ALTER COLUMN aml_risk_score DROP NOT NULL;
