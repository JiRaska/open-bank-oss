-- Hibernate Reactive + PanacheEntity allocate ids from a sequence named "<table>_seq"
-- (allocationSize 50). The CREATE TABLE migrations used BIGSERIAL (only "<table>_id_seq")
-- and the schema is generation:none, so every INSERT would fail at runtime with
-- relation "<table>_seq" does not exist. Same defect fixed for party (V6) and
-- notification (V4/V5). Repo convention: unquoted, lowercase, INCREMENT BY 50.
-- Rollback: DROP SEQUENCE balances_seq,balance_holds_seq,balance_outbox_seq,balance_reconciliation_seq;

CREATE SEQUENCE IF NOT EXISTS balances_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS balance_holds_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS balance_outbox_seq INCREMENT BY 50;
-- balance_reconciliation (PanacheEntity, added in V4__balance_reconciliation.sql / ADR-0039 Phase A)
-- was missed when the sequences were first backfilled — its INSERTs would fail the same way.
CREATE SEQUENCE IF NOT EXISTS balance_reconciliation_seq INCREMENT BY 50;
