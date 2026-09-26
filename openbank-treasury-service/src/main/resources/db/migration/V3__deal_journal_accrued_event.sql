-- SPDX-License-Identifier: Apache-2.0
-- ADR-0315 D5: daily interest accrual. Each calendar day of a SETTLED deal posts one ACCRUED journal
-- under `treasury:<dealId>:accrued:<yyyy-mm-dd>` (the ledger deduplicates on it), so deal_journals
-- must accept the event. The key column already holds 128 characters, enough for the date suffix.
--
-- Rollback: DELETE FROM deal_journals WHERE event = 'ACCRUED'; then restore the V1 constraint.

ALTER TABLE deal_journals DROP CONSTRAINT IF EXISTS deal_journals_event_check;
ALTER TABLE deal_journals ADD CONSTRAINT deal_journals_event_check
    CHECK (event IN ('SETTLED', 'ACCRUED', 'MATURED', 'REVERSED'));
