-- Rollback: retain revoked_at and the index. An older binary ignores revoked credentials,
-- so binary rollback after the first revocation is unsafe; recover with a forward fix.
-- Additive migration; retain keys, decision evidence and revocation markers on rollback.
-- Older binaries ignore revoked_at: do not restore one after any credential was revoked.
ALTER TABLE sca_enrolled_devices ADD COLUMN revoked_at TIMESTAMPTZ;
CREATE INDEX idx_sca_device_decisions_credential ON sca_device_decisions (credential_id);
