-- Hibernate Reactive + PanacheEntity allocate ids from a sequence named "<table>_seq"
-- (allocationSize 50). The CREATE TABLE migration used BIGSERIAL (only "<table>_id_seq")
-- and the schema is generation:none, so every INSERT would fail with
-- relation "<table>_seq" does not exist. Repo convention: unquoted, lowercase, INCREMENT BY 50.
-- Rollback: DROP SEQUENCE document_outbox_seq;

CREATE SEQUENCE IF NOT EXISTS document_outbox_seq INCREMENT BY 50;
