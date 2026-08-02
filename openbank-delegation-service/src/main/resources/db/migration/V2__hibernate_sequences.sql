-- Hibernate Reactive + PanacheEntity allocate ids from a sequence named "<table>_seq"
-- (allocationSize 50). V1 created the outbox id as BIGSERIAL, which only makes
-- "delegation_outbox_id_seq", and the schema is generation:none — so every outbox INSERT
-- failed at runtime with relation "delegation_outbox_seq" does not exist, taking the whole
-- transaction with it. Every lifecycle transition writes an outbox row in the same
-- transaction as the status change (ADR-0003/0050), so offer, accept, revoke and suspend
-- were all affected: nothing could be persisted at all.
--
-- Not caught earlier because the unit tests mock DelegationRepository and the boot smoke test
-- writes nothing — the fleet lesson that a mocked repository cannot prove outbox atomicity.
-- DelegationExpirationSweepIT, which drives the real scheduler against a real Postgres, is
-- what surfaced it.
--
-- Same defect already fixed for consent (V4), party (V6) and notification (V4/V5).
-- Repo convention: unquoted, lowercase, INCREMENT BY 50.
-- Rollback: DROP SEQUENCE delegation_outbox_seq;

CREATE SEQUENCE IF NOT EXISTS delegation_outbox_seq INCREMENT BY 50;
