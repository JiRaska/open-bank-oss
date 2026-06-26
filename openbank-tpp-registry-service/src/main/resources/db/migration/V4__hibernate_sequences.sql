-- Hibernate Reactive + PanacheEntity allocate ids from a sequence named "<table>_seq"
-- (allocationSize 50). The CREATE TABLE migrations used BIGSERIAL (only "<table>_id_seq")
-- and the schema is generation:none, so every INSERT would fail with
-- relation "<table>_seq" does not exist. Same defect fixed for party (V6) and notification
-- (V4/V5). Repo convention: unquoted, lowercase, INCREMENT BY 50.
-- Rollback: DROP SEQUENCE eba_sync_state_seq,tpp_entries_seq,tpp_outbox_seq;

CREATE SEQUENCE IF NOT EXISTS eba_sync_state_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS tpp_entries_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS tpp_outbox_seq INCREMENT BY 50;
