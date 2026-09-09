-- SPDX-License-Identifier: Apache-2.0
-- Additive migration. Rollback: pause writers, drain new allowance commands, retain evidence.
-- See docs/credit-risk-rollout.md; an old dispatcher cannot consume the new command type.
-- Do not backfill old decisions with ratios from the new policy.
ALTER TABLE loan_application ADD COLUMN existing_debt_outstanding numeric(20,2);
ALTER TABLE loan_application ADD COLUMN decision_dsti numeric(38,18);
ALTER TABLE loan_application ADD COLUMN decision_dti numeric(38,18);
ALTER TABLE loan_application ADD CONSTRAINT loan_application_existing_debt_nonnegative
    CHECK (existing_debt_outstanding IS NULL OR existing_debt_outstanding >= 0);

-- Daily keys preserve prior monthly snapshots. Rollback leaves this wider column intact.
ALTER TABLE loan_provisioning ALTER COLUMN period TYPE varchar(10);
