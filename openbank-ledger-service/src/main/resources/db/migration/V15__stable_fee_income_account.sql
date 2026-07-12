-- V1's '4001 Fee Income' row was seeded with gen_random_uuid() (openbank-ledger-service's
-- original scaffolding migration, before the stable-UUID chart convention existed) — its id is
-- different every deployment, so no consuming service could ever hardcode a working reference to
-- it (issue #468, discovered while adding openbank-billing-service's Pact contract: every real
-- fee-charge posting 422'd with "GL account not found").
--
-- Adds a new, stable-UUID Fee Income account instead of mutating V1's existing row: per
-- CLAUDE.md, an already-applied migration is never edited, and UPDATEing the row's primary key
-- risks orphaning any journal_lines that (however unlikely, given the id was unpredictable)
-- already reference the old random id. Same convention as V5 (last UUID segment = account code).
INSERT INTO gl_accounts (id, code, name, type, currency_code, is_leaf, is_enabled) VALUES
    ('a0000000-0000-0000-0000-000000004003', '4003', 'Fee Income', 'INCOME', 'CZK', true, true);

-- Rollback:
--   DELETE FROM gl_accounts WHERE id = 'a0000000-0000-0000-0000-000000004003';
-- Safe to roll back only before any journal_lines reference this account (FK would block the
-- DELETE otherwise) — i.e. before openbank-billing-service's fee-income config is pointed at it
-- in a live environment.
