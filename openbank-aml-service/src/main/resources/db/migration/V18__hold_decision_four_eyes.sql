-- ADR-0374 point 3: the decision on a held payment's case takes two analysts. One proposes an
-- outcome (CLEAR or BLOCK) and a reason; a different one confirms it by restating both. These
-- columns hold the proposal; the confirmation is the existing decided_by/decided_at/status.
--
-- Four nullable columns with no default: metadata-only on Postgres, no rewrite, no backfill.
-- Only cases with origin = 'PAYMENT_HOLD_INTAKE' ever carry values.
--
-- Rolling deploy: a pod from before this migration does not map these columns. Its updates write
-- only the columns it maps (Hibernate updates mapped columns of a managed entity), so it never
-- clears a proposal. What such a pod still does is decide a hold case in ONE step through
-- PUT /decision, so the held-payment intake (AML_PAYMENT_HELD_CONSUMER_ENABLED, off by default)
-- must stay off until every pod runs a build that contains this migration.
--
-- Rollback: roll the code back first, then
--   ALTER TABLE aml_cases DROP COLUMN hold_proposed_at;
--   ALTER TABLE aml_cases DROP COLUMN hold_proposed_by;
--   ALTER TABLE aml_cases DROP COLUMN hold_proposal_reason;
--   ALTER TABLE aml_cases DROP COLUMN hold_proposal_outcome;
-- and delete this file's row from flyway_schema_history. Leaving the columns in place is also
-- safe: the old code ignores them. Data lost on dropping them: pending proposals (none are
-- confirmed by a dropped column; a confirmed decision is already in status/decided_by) and the
-- name of the proposer of confirmed decisions. Turn the intake off before rolling back, since the
-- old code decides a hold case with one analyst.
ALTER TABLE aml_cases ADD COLUMN hold_proposal_outcome VARCHAR(16);
ALTER TABLE aml_cases ADD COLUMN hold_proposal_reason TEXT;
ALTER TABLE aml_cases ADD COLUMN hold_proposed_by VARCHAR(255);
ALTER TABLE aml_cases ADD COLUMN hold_proposed_at TIMESTAMPTZ;
