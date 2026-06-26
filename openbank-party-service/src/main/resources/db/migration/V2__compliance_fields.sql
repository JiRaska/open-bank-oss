-- AML 5AMLD + GDPR + CNB: Party/Customer compliance enhancements
-- V2: PEP flag, sanctions, GDPR consent, data retention, onboarding

ALTER TABLE parties
    ADD COLUMN IF NOT EXISTS pep_flag              BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS pep_category          VARCHAR(50),
    ADD COLUMN IF NOT EXISTS sanctions_checked_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sanctions_list_version VARCHAR(20),
    ADD COLUMN IF NOT EXISTS gdpr_consent_at       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS gdpr_consent_version  VARCHAR(10),
    ADD COLUMN IF NOT EXISTS marketing_consent     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS data_retention_until  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS onboarding_channel    VARCHAR(20) DEFAULT 'API',
    ADD COLUMN IF NOT EXISTS onboarding_agent_id   VARCHAR(100),
    ADD COLUMN IF NOT EXISTS risk_rating           VARCHAR(20) DEFAULT 'MEDIUM',
    ADD COLUMN IF NOT EXISTS risk_rated_at         TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_review_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS next_review_due       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS fatca_status          VARCHAR(20),
    ADD COLUMN IF NOT EXISTS crs_status            VARCHAR(20),
    ADD COLUMN IF NOT EXISTS deleted_at            TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deletion_reason       VARCHAR(100);

-- AML: PEP and high-risk customer indexes
CREATE INDEX IF NOT EXISTS idx_parties_pep ON parties(pep_flag) WHERE pep_flag = TRUE;
CREATE INDEX IF NOT EXISTS idx_parties_risk ON parties(risk_rating);
CREATE INDEX IF NOT EXISTS idx_parties_review ON parties(next_review_due) WHERE next_review_due IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_parties_sanctions ON parties(sanctions_checked_at);
CREATE INDEX IF NOT EXISTS idx_parties_deleted ON parties(deleted_at) WHERE deleted_at IS NOT NULL;

-- GDPR: Soft delete support
ALTER TABLE party_documents
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deletion_reason VARCHAR(100);

COMMENT ON COLUMN parties.pep_flag IS 'AML 5AMLD Art. 20: Politically Exposed Person flag';
COMMENT ON COLUMN parties.risk_rating IS 'AML CDD: LOW/MEDIUM/HIGH - drives review frequency';
COMMENT ON COLUMN parties.gdpr_consent_at IS 'GDPR Art. 7: Timestamp of explicit consent';
COMMENT ON COLUMN parties.data_retention_until IS 'GDPR Art. 17 + AML: 5 years after relationship end';
COMMENT ON COLUMN parties.fatca_status IS 'FATCA: US person status for tax reporting';
COMMENT ON COLUMN parties.crs_status IS 'CRS/OECD: Common Reporting Standard status';
COMMENT ON COLUMN parties.deleted_at IS 'GDPR Art. 17: Right to erasure - soft delete';
