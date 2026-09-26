-- Rollout: stop previous exporters before starting token-aware exporters. The columns are
-- additive, but previous code does not fence finalization by token. Keep pending rows and
-- allow outstanding old claims to expire through the existing two-minute lease before resuming.
-- Rollback: stop both commitment relays, then ALTER TABLE context_audit_commitment_outbox
-- DROP COLUMN claim_token; ALTER TABLE context_disclosure_commitment_outbox DROP COLUMN claim_token.
-- Deploy the previous relay only after stopping all token-aware workers; retain every evidence row.
-- Existing DISPATCHING rows have no token and remain eligible for the existing stale-claim retry.
ALTER TABLE context_audit_commitment_outbox ADD COLUMN claim_token uuid;
ALTER TABLE context_disclosure_commitment_outbox ADD COLUMN claim_token uuid;
