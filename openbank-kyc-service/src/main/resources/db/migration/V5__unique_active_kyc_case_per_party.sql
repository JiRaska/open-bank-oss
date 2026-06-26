-- ADR-0068: at most one ACTIVE KYC case per party.
--
-- Backs the idempotency guarantee of KycService.openCaseForParty, the target of the
-- PARTY_CREATED auto-open consumer. The application-level check (findByPartyId ?: openCase)
-- is race-free under the current single-consumer deployment, but under topic replay or a
-- future multi-pod scale-out two concurrent inserts for the same party could otherwise both
-- succeed. This partial unique index makes the database reject the second insert, so duplicate
-- open cases can never accumulate in the compliance queue.
--
-- Partial (active states only) so a party can still be re-KYC'd after its case reaches a
-- terminal state (APPROVED / REJECTED / EXPIRED) — a full UNIQUE(party_id) would wrongly
-- forbid periodic / post-expiry re-verification.
--
-- Plain CREATE UNIQUE INDEX (not CONCURRENTLY): it runs inside Flyway's migration transaction
-- and yields a stable checksum. The table is small and new, so a brief lock is fine.
CREATE UNIQUE INDEX uq_kyc_cases_active_party
    ON kyc_cases (party_id)
    WHERE status NOT IN ('APPROVED', 'REJECTED', 'EXPIRED');

-- Rollback:
--   DROP INDEX uq_kyc_cases_active_party;
