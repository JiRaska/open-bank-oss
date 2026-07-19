-- Duplicate party identity merge (ADR-0179).
--
-- `merged_into` records the surviving party when a duplicate is retired with status MERGED.
-- Nullable: only ever set on a MERGED row. Self-referencing FK so the relation is traversable
-- both ways (survivor -> retired duplicates via an index scan, retired -> survivor via the FK).
--
-- RESTRICT rather than CASCADE so a real row DELETE cannot silently orphan the pointer. Note
-- this is belt-and-braces only: nothing in the service DELETEs a party row today — GDPR Art. 17
-- erasure UPDATEs the row in place (PartyRepositoryImpl.anonymize), which no FK action covers.
ALTER TABLE parties ADD COLUMN IF NOT EXISTS merged_into UUID;

ALTER TABLE parties
    DROP CONSTRAINT IF EXISTS fk_parties_merged_into;
ALTER TABLE parties
    ADD CONSTRAINT fk_parties_merged_into
    FOREIGN KEY (merged_into) REFERENCES parties (party_id) ON DELETE RESTRICT;

-- A party can never be merged into itself.
ALTER TABLE parties
    DROP CONSTRAINT IF EXISTS ck_parties_merged_into_not_self;
ALTER TABLE parties
    ADD CONSTRAINT ck_parties_merged_into_not_self
    CHECK (merged_into IS NULL OR merged_into <> party_id);

-- Two directions, deliberately NOT a biconditional:
--
--   1. MERGED always carries a pointer — the status is meaningless without a survivor to follow.
--   2. A pointer may only appear on MERGED or CLOSED.
--
-- CLOSED is in (2) because GDPR Art. 17 erasure of a retired duplicate flips MERGED -> CLOSED
-- in place (PartyRepositoryImpl.anonymize) without touching merged_into. A strict biconditional
-- would abort that UPDATE, making a merged duplicate impossible to erase — an Art. 17 request
-- would fail outright. The pointer is a lineage reference, not personal data, so it survives
-- erasure; the PII on the row is anonymized as usual.
ALTER TABLE parties
    DROP CONSTRAINT IF EXISTS ck_parties_merged_into_iff_merged;
ALTER TABLE parties
    DROP CONSTRAINT IF EXISTS ck_parties_merged_into_status;
ALTER TABLE parties
    ADD CONSTRAINT ck_parties_merged_into_status
    CHECK (
        (status <> 'MERGED' OR merged_into IS NOT NULL)
        AND (merged_into IS NULL OR status IN ('MERGED', 'CLOSED'))
    );

-- "Which parties were merged into this survivor?" — the reporting/lineage direction.
-- Partial: only MERGED rows carry the column, so the index stays small.
CREATE INDEX IF NOT EXISTS idx_parties_merged_into
    ON parties (merged_into) WHERE merged_into IS NOT NULL;
