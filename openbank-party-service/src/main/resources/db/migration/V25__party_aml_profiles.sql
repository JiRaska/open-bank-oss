-- Personal AML profile (AML Act 253/2008 §9 + FATCA/CRS self-certification).
-- One row per declared VERSION: the newest row per party is flagged is_current, older rows are
-- kept unchanged as the audit history of what the customer declared and when. The four facts
-- other services read (pep_flag, pep_category, fatca_status, crs_status) stay on `parties` — V2
-- created those columns — and are derived from the current row on every declaration.
--
-- Rollback (the table is additive; nothing else references it):
--   DROP TABLE party_aml_profiles; DROP SEQUENCE IF EXISTS party_aml_profiles_seq;
-- The derived `parties` columns pre-date this migration and are left in place.

CREATE TABLE party_aml_profiles (
    id                          BIGINT PRIMARY KEY,
    party_id                    UUID NOT NULL,
    version                     INTEGER NOT NULL,
    is_current                  BOOLEAN NOT NULL,
    purposes                    VARCHAR(255) NOT NULL,
    purpose_note                VARCHAR(500),
    income_sources              VARCHAR(255) NOT NULL,
    income_note                 VARCHAR(500),
    occupation                  VARCHAR(32) NOT NULL,
    occupation_note             VARCHAR(500),
    expected_monthly_turnover   VARCHAR(32) NOT NULL,
    cash_intensive              BOOLEAN NOT NULL,
    is_pep                      BOOLEAN NOT NULL,
    pep_category                VARCHAR(50),
    pep_detail                  VARCHAR(500),
    -- JSON array of {"country": "<alpha-2>", "tin": "<string|null>"}, in declaration order.
    tax_residencies             TEXT NOT NULL,
    us_person                   BOOLEAN NOT NULL,
    truthful                    BOOLEAN NOT NULL,
    risk_factors                VARCHAR(255) NOT NULL,
    declared_at                 TIMESTAMPTZ NOT NULL,
    declared_by                 VARCHAR(100) NOT NULL,
    CONSTRAINT chk_party_aml_profile_truthful CHECK (truthful),
    CONSTRAINT chk_party_aml_profile_version CHECK (version >= 1),
    CONSTRAINT chk_party_aml_profile_pep CHECK (is_pep OR pep_category IS NULL)
);

CREATE UNIQUE INDEX uq_party_aml_profiles_version ON party_aml_profiles (party_id, version);
-- At most one current declaration per party.
CREATE UNIQUE INDEX uq_party_aml_profiles_current ON party_aml_profiles (party_id) WHERE is_current;

-- Unquoted, lowercase, INCREMENT BY 50 — the convention V19 restored after V16/V18 got it wrong.
CREATE SEQUENCE IF NOT EXISTS party_aml_profiles_seq INCREMENT BY 50;

COMMENT ON TABLE party_aml_profiles IS 'AML Act §9 customer declaration + FATCA/CRS self-certification, versioned; history retained';
