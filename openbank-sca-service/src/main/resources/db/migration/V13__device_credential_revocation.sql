-- Additive migration; retain keys, decision evidence and revocation markers on rollback.
-- Older binaries ignore revoked_at: do not restore one after any credential was revoked.
ALTER TABLE sca_enrolled_devices ADD COLUMN revoked_at TIMESTAMPTZ;
CREATE INDEX idx_sca_device_decisions_credential ON sca_device_decisions (credential_id);
