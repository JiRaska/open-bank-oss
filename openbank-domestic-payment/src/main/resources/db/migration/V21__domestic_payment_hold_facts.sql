-- ADR-0374 (payment admission): three facts about a held payment the row could not record.
--
-- 1. held_at: when the admission chain first held the payment. The hold's expiry and its age are
--    measured from here. Until now the only reading was updated_at, which is the hold time only
--    as long as nothing else writes the row.
-- 2. hold_case_requested_at: when the PAYMENT_HELD event that opens the hold's case was written,
--    set in the same transaction as that event. A repeated admission of the payment (a retried
--    activity attempt, or a run started to recover a hold whose run died) reads it and answers
--    "held for review" without asking any control again and without a second event.
-- 3. admission_fraud_*: the first fraud score the chain obtained for the payment, reused by every
--    later evaluation. fraud-service keeps and counts every score it gives, so scoring one payment
--    on each re-evaluation would add to what it holds against the account.
--
-- All five columns are nullable with no default: metadata-only, no rewrite, no backfill. A pod from
-- before this migration neither reads nor writes them and is unaffected; rows it writes carry NULL,
-- which every reader treats as "not held / not requested / not scored".
--
-- ROLLBACK: deploy the previous code first, with openbank.domestic.admission.enabled off and no
-- payment HELD, then
--   ALTER TABLE domestic_payments
--       DROP COLUMN held_at, DROP COLUMN hold_case_requested_at, DROP COLUMN admission_fraud_verdict,
--       DROP COLUMN admission_fraud_score, DROP COLUMN admission_fraud_rule_version;
--   DROP INDEX IF EXISTS idx_domestic_payments_held;
-- and delete this file's row from flyway_schema_history. Leaving the columns in place is harmless
-- and keeps the evidence of past holds.
ALTER TABLE domestic_payments
    ADD COLUMN held_at TIMESTAMPTZ,
    ADD COLUMN hold_case_requested_at TIMESTAMPTZ,
    ADD COLUMN admission_fraud_verdict VARCHAR(32),
    ADD COLUMN admission_fraud_score INTEGER,
    ADD COLUMN admission_fraud_rule_version VARCHAR(128);

-- Partial: the reconciliation sweep and the hold-age gauge only ever look at HELD rows.
CREATE INDEX idx_domestic_payments_held
    ON domestic_payments (redrive_attempts, updated_at)
    WHERE status = 'HELD';
