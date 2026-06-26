-- PSD2 RTS on SCA + Open Banking: Consent compliance
-- V2: Redirect URI, TPP details, SCA method, audit trail

ALTER TABLE consents
    ADD COLUMN IF NOT EXISTS redirect_uri          VARCHAR(500),
    ADD COLUMN IF NOT EXISTS tpp_name              VARCHAR(255),
    ADD COLUMN IF NOT EXISTS tpp_roles             VARCHAR(100),
    ADD COLUMN IF NOT EXISTS sca_method            VARCHAR(20),
    ADD COLUMN IF NOT EXISTS sca_reference         VARCHAR(100),
    ADD COLUMN IF NOT EXISTS frequency_per_day     INTEGER DEFAULT 4,
    ADD COLUMN IF NOT EXISTS combined_service_flag BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS last_action_date      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS revoked_by            VARCHAR(100),
    ADD COLUMN IF NOT EXISTS revocation_reason     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS ip_address            VARCHAR(45),
    ADD COLUMN IF NOT EXISTS user_agent            VARCHAR(500);

-- PSD2: Frequency limit enforcement
ALTER TABLE consents
    ADD CONSTRAINT chk_consent_frequency CHECK (frequency_per_day BETWEEN 1 AND 4);

CREATE INDEX IF NOT EXISTS idx_consents_tpp ON consents(tpp_name) WHERE tpp_name IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_consents_sca ON consents(sca_reference) WHERE sca_reference IS NOT NULL;

COMMENT ON COLUMN consents.frequency_per_day IS 'PSD2 RTS Art. 36: Max 4 AIS requests per day without SCA';
COMMENT ON COLUMN consents.combined_service_flag IS 'PSD2 RTS Art. 36(6): Combined AIS+PIS service';
COMMENT ON COLUMN consents.tpp_roles IS 'PSD2: AISP/PISP/CBPII roles';
