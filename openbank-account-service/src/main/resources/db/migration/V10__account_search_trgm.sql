-- OpenBank Account Service — Fuzzy account search
-- V10__account_search_trgm.sql
--
-- Why: operators need to locate an account by typing a *fragment* of its IBAN
-- (or the printed account number), not just an exact IBAN or a known party UUID.
-- A plain `LIKE '%frag%'` cannot use a B-tree index, so without a trigram index
-- every search is a full table scan. pg_trgm's GIN index makes substring LIKE
-- index-backed.
--
-- Availability / lock posture (money-path table):
--   This migration runs at service bootstrap, when `accounts` is empty (first
--   deploy) or small, so a plain CREATE INDEX builds in milliseconds and the brief
--   ShareLock it holds is a non-issue. CREATE INDEX **CONCURRENTLY** is deliberately
--   NOT used here: CONCURRENTLY cannot run inside a transaction AND must wait for all
--   concurrent transactions on the table to drain — during Quarkus startup the app's
--   own JDBC/reactive pool is connecting at the same time, so a CONCURRENTLY build in
--   the boot migration races those connections and is cancelled by lock_timeout
--   (verified). Bootstrap migrations and CONCURRENTLY do not mix.
--
--   OPERATIONAL NOTE — if this index ever has to be (re)built against an ALREADY
--   POPULATED production `accounts` table (e.g. a backfill or a rebuild after an
--   INVALID index), do it OUT OF BAND, not via a startup migration:
--       DROP INDEX CONCURRENTLY IF EXISTS idx_accounts_account_number_trgm;
--       CREATE INDEX CONCURRENTLY idx_accounts_account_number_trgm
--           ON accounts USING gin (account_number gin_trgm_ops);
--   run from a maintenance job so writes keep flowing during the build.
--
-- Security note (see docs/threat-models/openbank-account-service.md): this is an
-- account-enumeration surface. The risk is bounded in the application layer by a
-- minimum query length, a capped page size, RBAC on the endpoint and gateway rate
-- limiting — the index itself only changes the access path, not who may query.
--
-- Rollback: DROP INDEX IF EXISTS idx_accounts_account_number_trgm;
--           (pg_trgm extension is left in place; it is harmless and may be shared.)

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_accounts_account_number_trgm
    ON accounts USING gin (account_number gin_trgm_ops);
