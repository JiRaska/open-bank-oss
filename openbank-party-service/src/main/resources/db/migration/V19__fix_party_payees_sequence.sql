-- V18 created the pooled id sequence as the QUOTED "party_payees_SEQ", so Postgres kept its case.
-- Hibernate emits the name UNQUOTED, which folds to lowercase, so the two never met and every
-- PUT /api/v1/parties/{id}/payees answered 500 with
--   SQLGrammarException: relation "party_payees_seq" does not exist
-- from the day the endpoint shipped (#5913). V6 already fixed exactly this for the service's other
-- three sequences; V18 reintroduced it.
--
-- Safe to drop rather than rename: the quoted sequence has never been read (every insert failed),
-- so it holds no allocated state anything depends on. The lowercase sequence starts fresh, and
-- party_payees is empty for the same reason.
--
-- Rollback:
--   DROP SEQUENCE IF EXISTS party_payees_seq;
--   CREATE SEQUENCE IF NOT EXISTS "party_payees_SEQ" INCREMENT BY 50;

DROP SEQUENCE IF EXISTS "party_payees_SEQ";

CREATE SEQUENCE IF NOT EXISTS party_payees_seq INCREMENT BY 50;
