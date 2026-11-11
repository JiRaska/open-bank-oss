-- SPDX-License-Identifier: Apache-2.0
-- Screening inputs beyond the name (issue #15723).
--
-- 1. sanctions_checks records the explicit screening profile, the counterparty country and bank
--    identifier it was asked about, and the PEP outcome reported apart from the sanctions status.
--    All four are NULL on every existing row and on every profile-less (legacy) screen.
--
-- 2. sanctions_jurisdictions holds embargoed and high-risk JURISDICTIONS. No list source the
--    service imports carries them as a machine-readable feed (the FATF list import is skipped as
--    "not entity based"), so they are versioned reference data: a row is added, superseded or
--    retired only by a reviewed migration, never by a feed or an unreviewed endpoint.
--    This migration ships the table EMPTY on purpose. Which countries are listed, and under which
--    measure, is a compliance decision; until rows are added a country never matches.
--
--    measure = EMBARGO   -> the screen is a HIT regardless of the name.
--    measure = HIGH_RISK -> the screen is a POTENTIAL_HIT (manual review) regardless of the name.
--
-- Rollback: DROP TABLE sanctions_jurisdictions; ALTER TABLE sanctions_checks DROP COLUMN profile,
-- DROP COLUMN country, DROP COLUMN bank_identifier, DROP COLUMN pep_status. No existing column or
-- row is rewritten, so the previous release keeps working against the migrated schema.

-- IF NOT EXISTS: the legacy-schema regression tests pin Flyway below this version and add these
-- columns themselves so the current entity can read the pinned schema, then migrate forward.
ALTER TABLE sanctions_checks
    ADD COLUMN IF NOT EXISTS profile VARCHAR(16),
    ADD COLUMN IF NOT EXISTS country VARCHAR(2),
    ADD COLUMN IF NOT EXISTS bank_identifier VARCHAR(11),
    ADD COLUMN IF NOT EXISTS pep_status VARCHAR(16);

CREATE TABLE sanctions_jurisdictions (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    list_type     VARCHAR(30) NOT NULL,
    country_code  VARCHAR(2)  NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    measure       VARCHAR(16) NOT NULL CHECK (measure IN ('EMBARGO', 'HIGH_RISK')),
    -- Version of the published list this row was taken from; a new publication adds rows with a
    -- higher version and retires (active = false) the rows it supersedes, keeping the history.
    version       INTEGER     NOT NULL CHECK (version > 0),
    source_ref    TEXT        NOT NULL,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_sanctions_jurisdiction UNIQUE (list_type, country_code, version)
);

-- At most one ACTIVE row per (list, country): a superseding version must retire its predecessor.
CREATE UNIQUE INDEX uq_sanctions_jurisdiction_active
    ON sanctions_jurisdictions (list_type, country_code) WHERE active;
