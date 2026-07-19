-- Duplicate party identity merge (ADR-0179).
--
-- `merged_into` records the surviving party when a duplicate is retired with status MERGED.
-- Nullable: only ever set on a MERGED row. Self-referencing FK so the relation is traversable
-- both ways (survivor -> retired duplicates via an index scan, retired -> survivor via the FK).
--
-- RESTRICT, not CASCADE: erasing the survivor must not silently drop the pointer that explains
-- why the retired row exists. A survivor with merged duplicates has to be dealt with explicitly.
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

-- MERGED <-> merged_into are two halves of one fact; neither is valid alone.
-- Enforced in the DB because the pair is what every downstream reader keys off.
ALTER TABLE parties
    DROP CONSTRAINT IF EXISTS ck_parties_merged_into_iff_merged;
ALTER TABLE parties
    ADD CONSTRAINT ck_parties_merged_into_iff_merged
    CHECK ((status = 'MERGED') = (merged_into IS NOT NULL));

-- "Which parties were merged into this survivor?" — the reporting/lineage direction.
-- Partial: only MERGED rows carry the column, so the index stays small.
CREATE INDEX IF NOT EXISTS idx_parties_merged_into
    ON parties (merged_into) WHERE merged_into IS NOT NULL;
