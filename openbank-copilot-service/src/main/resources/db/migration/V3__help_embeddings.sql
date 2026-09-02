-- SPDX-License-Identifier: AGPL-3.0-only
-- Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

-- Semantic retrieval over the customer help corpus (ADR-0183, executed by ADR-0265 slice 4).
--
-- WHY pgvector ON THE EXISTING CLUSTER and not a vector database: ADR-0183 decided this in advance
-- — no new runtime, no new operator, no new backup/DR/residency path; the index inherits the CNPG
-- posture the copilot database already has.
--
-- CREATE EXTENSION IS NOT REDUNDANT AND IS NOT SUFFICIENT. `vector` is NOT a trusted extension, so
-- the owner role Flyway connects as CANNOT create it. Measured against the exact image the fleet
-- runs (ghcr.io/cloudnative-pg/postgresql:18.1, pgvector 0.8.1 present):
--   as the database owner: ERROR: permission denied to create extension "vector"
--                          HINT:  Must be superuser to create this extension.
--   as superuser:          CREATE EXTENSION
-- In the deployed cluster the extension is installed by the CloudNativePG `Database` resource
-- (gitops/components/copilot/copilot-db-database.yaml), which the operator applies with superuser
-- rights. This line is what makes local dev and the test containers work, where the app connects
-- as superuser — and it is safe in the deployed path because `IF NOT EXISTS` short-circuits before
-- the permission check, verified the same way:
--   as owner, extension already installed: NOTICE: extension "vector" already exists, skipping
-- Delete it and every developer's local database breaks; delete the Database resource and the
-- deployed migration fails at this line. Both halves are load-bearing.
CREATE EXTENSION IF NOT EXISTS vector;

-- One row per corpus chunk. The CONTENT is stored alongside the vector deliberately: retrieval
-- returns text, and re-reading the classpath resource to resolve an id would make the index and the
-- answer able to disagree after a redeploy.
CREATE TABLE IF NOT EXISTS help_passage_embedding (
    -- Deterministic: sha256(source || '#' || ordinal). Re-indexing the same corpus rewrites the
    -- same rows instead of accumulating duplicates, and no sequence is needed.
    chunk_id     text        PRIMARY KEY,
    source       text        NOT NULL,
    doc_title    text        NOT NULL,
    ordinal      integer     NOT NULL,
    content      text        NOT NULL,
    -- sha256 of `content`. The indexer re-embeds a chunk only when this changes, so a restart or a
    -- redeploy with an unchanged corpus costs zero embedding calls.
    content_hash text        NOT NULL,
    -- The model that produced `embedding`, stored per row rather than assumed globally: vectors from
    -- two different models are not comparable, and a model swap must be visible in the data rather
    -- than silently degrading similarity into noise.
    model        text        NOT NULL,
    -- 1024 = BAAI/bge-m3, the model declared in application.yaml. The width is FIXED here: changing
    -- the model to one with a different width needs a migration, which is the point — it forces the
    -- re-index that a silent swap would skip.
    embedding    vector(1024) NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_help_passage_source ON help_passage_embedding (source);

-- HNSW with cosine distance, matching the retrieval query's `<=>` operator. An index built for a
-- different operator class is simply not used — the query still returns correct results, just by
-- sequential scan, which is the kind of "working but pointless" state that hides for months.
--
-- HNSW rather than IVFFlat: IVFFlat must be built AFTER the data is loaded to pick its lists, and a
-- migration runs before the first row exists, so an IVFFlat index created here would be built on an
-- empty table and perform badly forever. HNSW has no such ordering requirement.
CREATE INDEX IF NOT EXISTS idx_help_passage_embedding_hnsw
    ON help_passage_embedding USING hnsw (embedding vector_cosine_ops);

-- ROLLBACK NOTE (rules.yaml db_change): forward-only, and safely reversible by dropping. The table
-- holds a DERIVED index over markdown that lives in the repo — no source of truth is here, so
-- `DROP TABLE help_passage_embedding;` loses nothing that cannot be rebuilt by the next indexer
-- run. The extension is left in place on rollback (dropping it would need superuser and affects
-- nothing else). Retrieval falls back to keyword-only automatically when the table is empty or
-- absent, and reports that it did.
