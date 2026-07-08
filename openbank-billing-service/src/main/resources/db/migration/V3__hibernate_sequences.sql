-- Hibernate Reactive + PanacheEntity (BillingOutboxEntity) allocates ids from a sequence named
-- "<table>_seq" (allocationSize 50) — the CREATE TABLE migration used BIGSERIAL (only
-- "<table>_id_seq") and the schema is generation:none, so an INSERT would fail with relation
-- "billing_outbox_seq" does not exist. Same defect fixed fleet-wide for interest (V5), party
-- (V6), notification (V4/V5), etc. Repo convention: unquoted, lowercase, INCREMENT BY 50.
-- assessed_fee / billing_cycle_assessment use PanacheEntityBase with an explicit UUID @Id
-- (no Hibernate-allocated sequence — id is assigned in code, mirroring InterestEntities.kt),
-- so only the outbox entity needs this.
-- Rollback: DROP SEQUENCE billing_outbox_seq;

CREATE SEQUENCE IF NOT EXISTS billing_outbox_seq INCREMENT BY 50;
