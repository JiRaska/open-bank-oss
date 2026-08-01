-- SPDX-License-Identifier: Apache-2.0
-- ADR-0213 decision-engine wiring: the financial inputs the policy evaluator reads
-- (missing input fails closed to REFER, D2) and the persisted evaluation record —
-- outcome, price band, reason codes, matched rules, policy versions and the SHA-256
-- input snapshot hash (ADR-0213 D1 output contract, ADR-0214 evidence).
--
-- Rollback: ALTER TABLE loan_application
--             DROP COLUMN verified_income_monthly, DROP COLUMN existing_debt_service_monthly,
--             DROP COLUMN age_years, DROP COLUMN residency, DROP COLUMN employment_tenure_months,
--             DROP COLUMN decision_outcome, DROP COLUMN decision_price_band,
--             DROP COLUMN decision_reasons, DROP COLUMN decision_matched_rules,
--             DROP COLUMN policy_versions, DROP COLUMN decision_input_hash,
--             DROP COLUMN decided_engine_at;

ALTER TABLE loan_application
    ADD COLUMN verified_income_monthly      NUMERIC(20,2),
    ADD COLUMN existing_debt_service_monthly NUMERIC(20,2),
    ADD COLUMN age_years                    INT,
    ADD COLUMN residency                    VARCHAR(8),
    ADD COLUMN employment_tenure_months     INT,
    ADD COLUMN decision_outcome             VARCHAR(16),
    ADD COLUMN decision_price_band          VARCHAR(32),
    ADD COLUMN decision_reasons             TEXT,
    ADD COLUMN decision_matched_rules       TEXT,
    ADD COLUMN policy_versions              TEXT,
    ADD COLUMN decision_input_hash          VARCHAR(64),
    ADD COLUMN decided_engine_at            TIMESTAMPTZ;
