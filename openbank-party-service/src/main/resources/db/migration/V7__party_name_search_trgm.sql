-- SPDX-License-Identifier: MPL-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
--
-- ADR-0055 Phase 2 (party-service): bounded name search.
-- Trigram GIN indexes back the case-insensitive substring search over the party's
-- legal name and trading name (GET /api/v1/parties/search?q=). Indexing lower(...)
-- matches the query predicate `lower(legal_name) LIKE ?` so the planner uses the index
-- instead of a sequential scan; pg_trgm makes non-anchored ILIKE/LIKE selective.
--
-- Scope note (GDPR Art. 5 data-minimisation): ONLY name columns are indexed/searchable.
-- The birth number (rodné číslo) is deliberately NOT searchable here — it is held
-- encrypted in pid-service and is never duplicated as plaintext into party-service.
--
-- Bootstrap-time DDL (not CONCURRENTLY): fast on an empty/small sandbox table. On a
-- large live table, build out-of-band with CREATE INDEX CONCURRENTLY first.
-- Rollback: DROP INDEX idx_parties_legal_name_trgm, idx_parties_trading_name_trgm;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_parties_legal_name_trgm
    ON parties USING gin (lower(legal_name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_parties_trading_name_trgm
    ON parties USING gin (lower(trading_name) gin_trgm_ops);
