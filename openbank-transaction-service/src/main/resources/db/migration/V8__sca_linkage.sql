-- ADR-0021 settlement gate: every customer-initiated movement records WHICH device-signed
-- SCA challenge authorised it (non-repudiation), or which documented exemption applied
-- (PSD2 RTS Art. 15 own-account transfers). Customer identity itself reuses the V2
-- compliance columns actor_id/actor_type that were already in place but never populated.
ALTER TABLE transactions ADD COLUMN sca_challenge_id UUID;
ALTER TABLE transactions ADD COLUMN sca_exemption VARCHAR(64);
