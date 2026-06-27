-- SPDX-License-Identifier: Apache-2.0
-- Sanctions entries — one row per individual/org from a sanctions or PEP list.
-- search_text = normalize(primaryName + aliases), indexed with pg_trgm for
-- fuzzy similarity matching in screen().

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TABLE IF NOT EXISTS sanctions_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    list_type       VARCHAR(30) NOT NULL,
    external_id     VARCHAR(200),
    entity_type     VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL',
    primary_name    TEXT NOT NULL,
    aliases_json    TEXT NOT NULL DEFAULT '[]',
    date_of_birth   TEXT,
    nationalities   TEXT NOT NULL DEFAULT '[]',
    programs        TEXT NOT NULL DEFAULT '[]',
    -- Denormalized for search: unaccented lowercase of primary_name + all aliases, ' | '-separated.
    -- Populated by SanctionsImportService on every upsert.
    search_text     TEXT NOT NULL DEFAULT '',
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- NULL external_id allowed (some lists have no stable id); composite unique only when non-null.
    CONSTRAINT uq_entry UNIQUE NULLS NOT DISTINCT (list_type, external_id)
);

-- GIN trgm index — drives similarity() in screen() query
CREATE INDEX IF NOT EXISTS idx_entries_search_trgm
    ON sanctions_entries USING gin (search_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_entries_list_active
    ON sanctions_entries (list_type, active);
