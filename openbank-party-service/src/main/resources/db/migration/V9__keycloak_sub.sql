-- V8: Link parties to Keycloak identity for mobile self-registration (ADR-0068 Sprint 1)
-- One Keycloak sub = one party, enforced by unique index.
-- Rollback: ALTER TABLE parties DROP COLUMN keycloak_sub;
ALTER TABLE parties ADD COLUMN keycloak_sub VARCHAR(255);
CREATE UNIQUE INDEX uq_parties_keycloak_sub ON parties(keycloak_sub) WHERE keycloak_sub IS NOT NULL;
