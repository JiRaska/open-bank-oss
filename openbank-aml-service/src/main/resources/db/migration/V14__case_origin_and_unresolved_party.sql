-- Two facts an AML case could not record, both needed by the held-payment intake (ADR-0374 D13).
--
-- 1. origin: which entry point opened the case (REST, PAYMENT_HOLD_INTAKE, PACK_EVALUATION,
--    ONBOARDING_SCREENING). A rail releases a held payment on a decision published under the
--    case key `payment-hold:<rail>:<paymentId>`, and a key says nothing about who wrote the row
--    under it. The intake accepts as its case only a row whose origin it wrote itself; a row of
--    any other origin under that key is not adopted and cannot be decided. NULL means "written
--    before this column existed, or by a pod that predates it": unknown, never a system origin.
--
-- 2. party_id may be NULL. A rail that could not read the debtor's standing sends no party, and
--    the intake must open the case anyway instead of asking the account authority whose outage
--    usually caused that. NULL is the explicit, queryable "party not established"; the resolution
--    sweep fills it in from account_id. party_resolution_attempted_at is when that sweep last
--    tried and failed, so it can take the least recently attempted cases first and an
--    unresolvable case cannot starve the rest.
--
-- All three statements are metadata-only on Postgres: nullable columns with no default, and
-- DROP NOT NULL. No table rewrite, no backfill. The partial index covers only rows with no party.
--
-- Rolling deploy: a pod from before this migration neither writes nor reads the two new columns
-- and keeps inserting a non-null party_id, so it is unaffected by the schema. What it cannot do
-- is READ a row whose party_id is NULL. Such rows are written only by the held-payment intake,
-- which that pod does not contain and which stays off until a deployment turns it on, so turn
-- it on only once every pod runs a build that contains this migration.
--
-- Rollback: roll the code back first (the entity maps the new columns), then
--   UPDATE aml_cases SET party_id = account_id WHERE party_id IS NULL AND account_id IS NOT NULL;
--   -- any row still NULL has neither party nor account; decide those by hand before the next line
--   ALTER TABLE aml_cases ALTER COLUMN party_id SET NOT NULL;
--   DROP INDEX idx_aml_cases_party_missing;
--   ALTER TABLE aml_cases DROP COLUMN party_resolution_attempted_at;
--   ALTER TABLE aml_cases DROP COLUMN origin;
-- and delete this file's row from flyway_schema_history. The UPDATE puts unresolved cases back
-- into the shape the earlier sweep recognises (party_id = account_id), so they are still
-- resolved afterwards. Data lost on rollback: the provenance of every case, and with it the
-- protection in (1); the held-payment intake must be off before rolling back.
ALTER TABLE aml_cases ADD COLUMN origin VARCHAR(32);
ALTER TABLE aml_cases ADD COLUMN party_resolution_attempted_at TIMESTAMPTZ;
ALTER TABLE aml_cases ALTER COLUMN party_id DROP NOT NULL;

CREATE INDEX idx_aml_cases_party_missing
    ON aml_cases (party_resolution_attempted_at NULLS FIRST)
    WHERE party_id IS NULL;
