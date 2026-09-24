-- Rollback: stop the incident consumer, then DROP INDEX idx_context_incident_revision_digest
-- and DROP COLUMN content_digest. Retain the ledger while reconciliation is in progress;
-- dropping the digest loses the ability to distinguish a conflicting replay.
ALTER TABLE context_projection_events ADD COLUMN content_digest char(64);

-- Older observations have no digest and cannot be retroactively verified. New incident
-- revisions must have one content identity regardless of event type or redelivery key.
CREATE UNIQUE INDEX idx_context_incident_revision_digest
  ON context_projection_events(bank_scope, aggregate_ref, source_version)
  WHERE source_system = 'security-scanner' AND content_digest IS NOT NULL;
