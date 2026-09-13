-- ADR-0179 / issue #1984 (consumer adoption of `merged_into`): openbank-audit-service.
--
-- `audit_entries` is append-only at the DATABASE level (V2's `no_update_audit`/`no_delete_audit`
-- RULEs, `DO INSTEAD NOTHING`), so a party merge can never be handled by rewriting `aggregate_id`
-- on the historical rows recorded under the retired party's id — that write would silently no-op,
-- and even if it did not, a tamper-evident row is not a row this service may rewrite. Those rows
-- stay exactly as they were recorded, forever.
--
-- This table is not the audit trail and carries none of its guarantees. It is a small, ordinary
-- (mutable) lookup projection, consulted only at READ time, that lets a history query follow the
-- `merged_into` pointer BACKWARD — from a survivor to every id that was, transitively, merged
-- into it — without touching a single existing `audit_entries` row. See PartyMergeIndexRepository.
--
-- One row per merge. party-service's `mergeParty` use case rejects merging a party that is
-- already MERGED (`PartyService.kt`: "Party ... is already merged into ..."), so a given
-- `retired_party_id` can be the SOURCE of at most one merge ever — which is what makes it a safe
-- natural idempotency key for a Kafka redelivery of the same PARTY_MERGED event (see
-- PartyMergeIndexRepository.recordMerge). It does not, and need not, prevent the SURVIVOR from
-- later itself being merged elsewhere — that is exactly the chained case
-- (A -> B, then later B -> C) the reverse walk is written to follow.
--
-- Rollback: DROP TABLE IF EXISTS party_merge_index; DROP SEQUENCE IF EXISTS party_merge_index_seq;
CREATE TABLE IF NOT EXISTS party_merge_index (
    id                 BIGSERIAL   PRIMARY KEY,
    retired_party_id   UUID        NOT NULL UNIQUE,
    survivor_party_id  UUID        NOT NULL,
    recorded_at        TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The direction a history query actually needs: "which retired ids point AT this survivor",
-- the reverse of how the pointer is written (retired -> survivor).
CREATE INDEX IF NOT EXISTS idx_party_merge_index_survivor ON party_merge_index(survivor_party_id);

-- PanacheEntity allocates ids from a sequence named "<table>_seq" (allocationSize 50, repo
-- convention per V4/V7) — a plain BIGSERIAL alone only creates "<table>_id_seq", which Hibernate
-- never looks at.
CREATE SEQUENCE IF NOT EXISTS party_merge_index_seq INCREMENT BY 50;

COMMENT ON TABLE party_merge_index IS
    'ADR-0179 read-side projection: retired party id -> the survivor it was merged into. Deliberately mutable and NOT covered by the audit_entries immutability RULEs — it is a lookup index, never evidence in its own right (the PARTY_MERGED event itself is the evidence, and it is stored as an ordinary audit_entries row exactly as before).';

GRANT ALL ON party_merge_index TO openbank;
GRANT ALL ON SEQUENCE party_merge_index_seq TO openbank;
GRANT ALL ON SEQUENCE party_merge_index_id_seq TO openbank;
