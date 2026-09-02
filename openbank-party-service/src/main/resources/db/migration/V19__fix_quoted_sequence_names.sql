-- Fixes two Hibernate id sequences that Postgres and Hibernate spell differently, so every
-- insert into `party_payees` and `party_marketing_consent` fails at id allocation.
--
-- V5 made this exact mistake for parties/party_documents/party_outbox and V6 fixed it the same
-- way. V16 and V18 reintroduced it. A quoted `"x_SEQ"` keeps its case in Postgres, while the
-- `select nextval('x_SEQ')` Hibernate emits carries an UNQUOTED identifier inside the literal,
-- which Postgres folds to lower case — so it looks up `x_seq` and finds nothing:
--
--   ERROR: relation "party_payees_seq" does not exist (42P01) [select nextval('party_payees_SEQ')]
--
-- Observed live on four party endpoints in the authenticated fuzz lane (#5913). The
-- `party_marketing_consent` one has the identical shape and was found by grepping for it, not by
-- the fuzzer — it fails the same way on the first grant the projection consumer records.
--
-- Seeded from the table's own max(id) rather than restarting at 1: `id` is BIGSERIAL, so any row
-- written before this migration got its id from the implicit `party_payees_id_seq`, and a fresh
-- sequence starting at 1 would collide with it on the first insert.
--
-- Rollback:
--   DROP SEQUENCE IF EXISTS party_payees_seq;
--   DROP SEQUENCE IF EXISTS party_marketing_consent_seq;
--   CREATE SEQUENCE IF NOT EXISTS "party_payees_SEQ" INCREMENT BY 50;
--   CREATE SEQUENCE IF NOT EXISTS "party_marketing_consent_SEQ" INCREMENT BY 50;
--   (restores the broken state exactly; both endpoints resume answering 500)

DROP SEQUENCE IF EXISTS "party_payees_SEQ";
DROP SEQUENCE IF EXISTS "party_marketing_consent_SEQ";

DO $$
DECLARE
    next_payee  BIGINT;
    next_consent BIGINT;
BEGIN
    SELECT COALESCE(MAX(id), 0) + 50 INTO next_payee   FROM party_payees;
    SELECT COALESCE(MAX(id), 0) + 50 INTO next_consent FROM party_marketing_consent;

    EXECUTE format('CREATE SEQUENCE IF NOT EXISTS party_payees_seq INCREMENT BY 50 START WITH %s', next_payee);
    EXECUTE format('CREATE SEQUENCE IF NOT EXISTS party_marketing_consent_seq INCREMENT BY 50 START WITH %s', next_consent);
END
$$;

GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
