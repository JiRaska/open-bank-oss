-- Issue #3604 — the accept screen showed the counterparty as a truncated UUID, so the person
-- being asked to grant authority over their money could not tell who was asking. The display
-- name is SNAPSHOTTED onto the grant at offer time from the pid-service eligibility lookup
-- delegation-service already performs (ADR-0232 D5): no runtime lookup, no new authority for
-- customer-edge, and the label the record carries is the one that was true at the moment of
-- consent — a later rename must not silently rewrite who agreed to what.
--
-- Nullable, and left NULL for existing rows on purpose: there is no backfill. Manufacturing a
-- name for a grant that was consented to without one would put a value into an authorisation
-- record that was never part of it. Consumers fall back to the party id, exactly as today.
--
-- PII note: these are personal names at rest in one more service. That service already holds
-- who may touch whose money, and the columns are read only by a party to the grant (the read
-- paths are party-scoped in DelegationService) or by a role-gated bank operator.
--
-- Rollback:
--   ALTER TABLE delegation_grants DROP COLUMN grantor_name;
--   ALTER TABLE delegation_grants DROP COLUMN grantee_name;

ALTER TABLE delegation_grants ADD COLUMN IF NOT EXISTS grantor_name varchar(200);
ALTER TABLE delegation_grants ADD COLUMN IF NOT EXISTS grantee_name varchar(200);
