-- Audit the immutable first-placement decision separately from the legacy allocation columns.
-- Existing LEGACY and pre-snapshot BATCH plans cannot be truthfully backfilled; NULL means unknown.
-- Rollback: retain this column while any new plan can be replayed. Dropping it loses the original
-- provider/election/rule/rounding evidence and can permit a changed replay after a restart.
ALTER TABLE pension_subscription_plans
    ADD COLUMN decision_snapshot JSONB;
