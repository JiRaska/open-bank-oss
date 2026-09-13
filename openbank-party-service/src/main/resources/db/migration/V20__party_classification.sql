-- ADR-0252: bank-owned synthetic canaries are a distinct, immutable party origin.
-- Existing parties predate the fleet and are customers by definition.
-- Rollback: before this migration is applied, DROP COLUMN classification; never edit an applied Flyway migration.
ALTER TABLE parties
    ADD COLUMN classification VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER',
    ADD CONSTRAINT chk_parties_classification CHECK (classification IN ('CUSTOMER', 'SYNTHETIC'));
