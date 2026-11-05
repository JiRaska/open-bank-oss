-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 D7: the posting-rule matrix posts the leg-described products under their own events
-- (TRADE, SETTLEMENT, ACCRUAL, FIXING, PAYMENT, AMORTISATION, REVALUATION, MATURITY,
-- EARLY_TERMINATION, CORRECTION), keyed treasury:<dealId>:<event>:<flowId|date>. The legacy
-- products keep SETTLED / ACCRUED / MATURED / REVERSED. The key column (128) already fits a
-- flow-id suffix (~90 characters).
--
-- Rollback: DELETE FROM deal_journals WHERE event NOT IN ('SETTLED', 'ACCRUED', 'MATURED', 'REVERSED');
-- then restore the V3 constraint.
ALTER TABLE deal_journals DROP CONSTRAINT IF EXISTS deal_journals_event_check;
ALTER TABLE deal_journals ADD CONSTRAINT deal_journals_event_check
    CHECK (event IN ('SETTLED', 'ACCRUED', 'MATURED', 'REVERSED',
                     'TRADE', 'SETTLEMENT', 'ACCRUAL', 'FIXING', 'PAYMENT', 'AMORTISATION',
                     'REVALUATION', 'MATURITY', 'EARLY_TERMINATION', 'CORRECTION'));
