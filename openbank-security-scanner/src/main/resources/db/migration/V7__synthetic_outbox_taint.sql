-- Preserve ADR-0252 synthetic-test provenance across the durable ICT incident hand-off.
-- Existing rows predate synthetic journeys and therefore safely backfill to FALSE.
-- Rollback before a V7 producer writes data: ALTER TABLE ict_incident_outbox DROP COLUMN synthetic;

ALTER TABLE ict_incident_outbox ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT FALSE;
