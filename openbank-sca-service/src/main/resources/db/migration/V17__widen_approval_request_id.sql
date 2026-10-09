-- ADR-0335 / #12385: an APPROVAL challenge's approvalRequestId was VARCHAR(64), sized for a bare UUID.
-- pension-service's reserved-namespace ids are longer (`pension-exit:<64 hex>` is 77 characters,
-- `pension-onboarding:<uuid>:<uuid>` is 92), and a longer value reached Postgres as
-- `value too long` -> 400 at initiate. Widened to 160; the service now refuses a longer id at
-- initiate with an explicit 400 instead of leaving it to the column.
--
-- Rollback: ALTER TABLE sca_challenges ALTER COLUMN dynamic_approval_request_id TYPE VARCHAR(64);
--   (fails if any stored id is longer than 64 — check first:
--   SELECT count(*) FROM sca_challenges WHERE length(dynamic_approval_request_id) > 64;)
ALTER TABLE sca_challenges ALTER COLUMN dynamic_approval_request_id TYPE VARCHAR(160);
