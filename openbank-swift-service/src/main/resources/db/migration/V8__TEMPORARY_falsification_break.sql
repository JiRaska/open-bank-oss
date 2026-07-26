-- TEMPORARY — #2320 acceptance criteria: "break the boot deliberately and confirm CI goes red.
-- A boot smoke test that has only ever passed while excluded proves nothing."
-- This migration is deliberately invalid SQL. Flyway fails during boot, SwiftBootSmokeIT's
-- readiness assertion cannot pass, and the swift-service build must go RED.
-- REVERTED in the next commit on this branch. It must not reach main.
ALTER TABLE swift_outbox ADD COLUMN this_is_not_valid_sql_at_all !!!;
