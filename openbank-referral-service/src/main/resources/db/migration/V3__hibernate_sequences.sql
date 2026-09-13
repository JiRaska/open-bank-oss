-- Hibernate Reactive + PanacheEntity allocate ids from a sequence named "<table>_seq"
-- (allocationSize 50). The CREATE TABLE migration used BIGSERIAL (only "<table>_id_seq")
-- so the very first insert into referral_outbox fails with
-- relation "referral_outbox_seq" does not exist. Repo convention: unquoted, lowercase, INCREMENT BY 50.
-- Rollback: DROP SEQUENCE referral_outbox_seq;

CREATE SEQUENCE IF NOT EXISTS referral_outbox_seq INCREMENT BY 50;
