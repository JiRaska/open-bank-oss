-- ADR-0033 §D: the CAPITALIZING *claim* — the state that keeps interest-service and the GL from
-- disagreeing about how much interest a period actually credited.
--
-- The defect this closes. `InterestService.capitalize` posts the credit to ledger-service and only
-- then commits its own capitalization row (deliberately: the reverse order strands a credit the GL
-- never saw). The ledger idempotency key is the capitalization's business identity —
-- `interest-capitalization-{account}-{product}-{periodTo}` — and carries NO amount, because a key
-- that changed with the amount would simply post a SECOND journal instead of colliding.
--
-- So, before this migration:
--   1. capitalize() reads the ACCRUING accruals <= periodTo   -> gross 100
--   2. ledger books journal J(key, 100)
--   3. the pod dies before saveWithOutbox commits             -> accruals still ACCRUING, no cap row
--   4. a missed-day accrual for an earlier date is backfilled -> ACCRUING, also <= periodTo
--   5. the retry re-reads the set                             -> gross 120, SAME key
--   6. the ledger returns J(key, 100) from findByIdempotencyKey without looking at the amount
--   7. interest-service commits a cap row for 120 and flips everything CAPITALIZED
-- The customer and the GL moved 100; interest-service says 120; the withholding remittance then
-- pays real cash to the finanční úřad on 20 CZK that was never credited. Nothing reconciles the two
-- sides, so the break is permanent and silent.
--
-- The fix is to make step 1 a *claim* rather than a read: the selected accruals flip
-- ACCRUING -> CAPITALIZING in their own committed transaction BEFORE the ledger is told anything.
--   * A crash anywhere after the claim leaves the set CAPITALIZING. The retry finds THAT set
--     (InterestAccrualRepository.findClaimedForCapitalization), so it derives the identical amount
--     and the identical key, and the ledger collapses the replay onto the journal it already has.
--     A stuck CAPITALIZING set is therefore recoverable by a plain retry of capitalize(...) with the
--     same periodTo — no operator surgery, no manual status edit.
--   * The backfilled accrual of step 4 lands ACCRUING, outside the claim, and falls into the next
--     period. That is the correct answer: it was not part of the credit the ledger booked.
--
-- claimed_period_to records which periodTo the claim was made for. It is the guard against the one
-- remaining way to mint a second key: completing an in-flight claim under a DIFFERENT period end.
-- capitalize() refuses that combination loudly instead (see InterestService.inFlightClaimFailure).
--
-- 'CAPITALIZING' is appended AFTER 'ACCRUING' to keep the enum in lifecycle order. REVERSED and
-- SUSPENDED (V1) still have no writer and are deliberately left untouched.
--
-- PostgreSQL note: ALTER TYPE ... ADD VALUE is allowed inside Flyway's transaction on PG 12+ (this
-- fleet runs 16) as long as the new value is not USED in the same transaction. It is not — the
-- statements below only declare it and add a nullable column. For that reason there is deliberately
-- no partial index `WHERE status = 'CAPITALIZING'` here: that index expression would use the value
-- and fail the migration. idx_accruals_account already serves the claim lookup, which is rare
-- (one row set per in-flight capitalization) and always account+product scoped.
--
-- Rollback note (only safe while no row is CAPITALIZING; the value itself cannot be dropped from a
-- PG enum, which is harmless — it simply becomes unused again):
--   ALTER TABLE interest_accruals DROP COLUMN IF EXISTS claimed_period_to;
ALTER TYPE accrual_status ADD VALUE IF NOT EXISTS 'CAPITALIZING' AFTER 'ACCRUING';

ALTER TABLE interest_accruals ADD COLUMN IF NOT EXISTS claimed_period_to DATE;

COMMENT ON COLUMN interest_accruals.claimed_period_to IS
    'The capitalization periodTo this accrual was claimed for while status = CAPITALIZING; NULL otherwise.';
