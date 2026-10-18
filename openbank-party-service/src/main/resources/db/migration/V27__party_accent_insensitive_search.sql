-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- Accent-insensitive, partial name search (GET /api/v1/parties/search): an operator types
-- "novak jan" and must find "Jan Novák", in any language. party_fold() is lower(unaccent(x))
-- declared IMMUTABLE (unaccent itself is only STABLE, so it cannot back an index directly;
-- pinning the dictionary as 'public.unaccent' makes the wrapper safe to mark immutable).
-- unaccent is a TRUSTED extension, so the database owner Flyway connects as may create it.
--
-- Rollback: DROP INDEX idx_parties_legal_name_fold_trgm, idx_parties_trading_name_fold_trgm;
--           DROP FUNCTION party_fold(text);

CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE OR REPLACE FUNCTION party_fold(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $$ SELECT lower(public.unaccent('public.unaccent'::regdictionary, $1)) $$;

CREATE INDEX IF NOT EXISTS idx_parties_legal_name_fold_trgm
    ON parties USING gin (party_fold(legal_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_parties_trading_name_fold_trgm
    ON parties USING gin (party_fold(trading_name) gin_trgm_ops);
