-- ADR-0374 (payment admission): facts about a held payment the row could not record.
--
-- 1. held_at: when the admission chain first held the payment. The hold's expiry and its age are
--    measured from here. Until now the only reading was updated_at, which is the hold time only
--    as long as nothing else writes the row.
-- 2. hold_case_requested_at: when the PAYMENT_HELD event that opens the hold's case was written,
--    set in the same transaction as that event. A repeated admission of the payment (a retried
--    activity attempt, or a run started to recover a hold whose run died) reads it and answers
--    "held for review" without asking any control again and without a second event.
-- 3. hold_review, hold_case_reasons: which review of the hold is current (NULL reads as the
--    first), and what its case was asked to decide, as STEP:REASON pairs. A case decision covers
--    exactly those; a control that reports something else at release gets a review of its own.
-- 3b. hold_decision: what the hold's case decided (RELEASE or REJECT), recorded when the decision is
--    accepted. The workflow run reads it from the row, so a run that dies after the decision was
--    accepted loses nothing and the run that recovers the hold applies it.
-- 4. run_checked_at: when the sweep last offered the payment a workflow run. Ordering only, so the
--    least recently checked payments go first; redrive_attempts keeps its one meaning, the number
--    of re-drives that actually started a run.
-- 5. admission_fraud_*: the first fraud score the chain obtained for the payment, reused by every
--    later evaluation. fraud-service keeps and counts every score it gives, so scoring one payment
--    on each re-evaluation would add to what it holds against the account.
--
-- All nine columns are nullable with no default: metadata-only, no rewrite, no backfill. A pod from
-- before this migration neither reads nor writes them and is unaffected; rows it writes carry NULL,
-- which every reader treats as "not held / not requested / not scored".
--
-- ROLLBACK: deploy the previous code first, with openbank.domestic.admission.enabled off and no
-- payment HELD, then
--   ALTER TABLE domestic_payments
--       DROP COLUMN held_at, DROP COLUMN hold_case_requested_at, DROP COLUMN hold_decision,
--       DROP COLUMN hold_review, DROP COLUMN hold_case_reasons,
--       DROP COLUMN run_checked_at, DROP COLUMN admission_fraud_verdict,
--       DROP COLUMN admission_fraud_score, DROP COLUMN admission_fraud_rule_version;
--   DROP INDEX IF EXISTS idx_domestic_payments_held;
-- and delete this file's row from flyway_schema_history. Leaving the columns in place is harmless
-- and keeps the evidence of past holds.
ALTER TABLE domestic_payments
    ADD COLUMN held_at TIMESTAMPTZ,
    ADD COLUMN hold_case_requested_at TIMESTAMPTZ,
    ADD COLUMN hold_review INTEGER,
    ADD COLUMN hold_case_reasons VARCHAR(512),
    ADD COLUMN hold_decision VARCHAR(16),
    ADD COLUMN run_checked_at TIMESTAMPTZ,
    ADD COLUMN admission_fraud_verdict VARCHAR(32),
    ADD COLUMN admission_fraud_score INTEGER,
    ADD COLUMN admission_fraud_rule_version VARCHAR(128);

-- Partial: the reconciliation sweep and the hold-age gauge only ever look at HELD rows.
CREATE INDEX idx_domestic_payments_held
    ON domestic_payments (run_checked_at NULLS FIRST, updated_at)
    WHERE status = 'HELD';
