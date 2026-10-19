-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 D3: effective-dated, four-eyes reference data (calendars, indices, counterparties,
-- settlement instructions, netting agreements, securities, books, bucket sets, regulatory codes).
-- One generic versioned record per (kind, key, version); the payload is the kind's typed JSON
-- (validated by the domain before it is written). Valid over [valid_from, valid_to).
--
-- The counterparties table stays as a PROJECTION of the ACTIVE counterparty records (deals keep
-- their foreign key to it, and the limit check reads it); the reference store is authoritative and
-- an approval rewrites the projection in the same transaction.
--
-- Seeds: created by 'system:seed' (SYSTEM), ACTIVE without an approver — not four-eyed, which is
-- why verified = false wherever the content is not citable. Calendars mirror the kernel's
-- calendar sets and are verified; index conventions, books, bucket set and the migrated
-- counterparties are not.
--
-- Rollback (additive only; nothing outside this service references these tables):
--   DROP TABLE reference_commands, reference_records; DROP SEQUENCE reference_records_seq;
--   ALTER TABLE counterparties DROP CONSTRAINT counterparties_kind_check;
--   ALTER TABLE counterparties ADD CONSTRAINT counterparties_kind_check CHECK (kind IN ('BANK', 'CENTRAL_BANK'));

CREATE SEQUENCE IF NOT EXISTS reference_records_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE reference_records (
    id                BIGINT       PRIMARY KEY,
    kind              VARCHAR(32)  NOT NULL CHECK (kind IN ('calendar', 'index', 'counterparty', 'ssi',
                          'netting', 'security', 'book', 'bucket-set', 'regulatory-code')),
    record_key        VARCHAR(128) NOT NULL,
    version           INT          NOT NULL CHECK (version >= 1),
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'SUPERSEDED', 'REJECTED')),
    valid_from        DATE         NOT NULL,
    valid_to          DATE,
    verified          BOOLEAN      NOT NULL DEFAULT FALSE,
    payload           TEXT         NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    created_by_type   VARCHAR(20)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    reason            TEXT,
    approved_by       VARCHAR(255),
    approved_by_type  VARCHAR(20),
    approved_at       TIMESTAMPTZ,
    verified_by       VARCHAR(255),
    decision_reason   TEXT,
    CONSTRAINT reference_records_version_unique UNIQUE (kind, record_key, version),
    CONSTRAINT reference_records_range CHECK (valid_to IS NULL OR valid_to > valid_from),
    -- Four-eyes, a second time at the database (as deals_four_eyes).
    CONSTRAINT reference_records_four_eyes CHECK (approved_by IS NULL OR approved_by <> created_by),
    CONSTRAINT reference_records_verifier_not_creator CHECK (verified_by IS NULL OR verified_by <> created_by),
    CONSTRAINT reference_records_approver_human CHECK (approved_by_type IS NULL OR approved_by_type = 'HUMAN'),
    -- ACTIVE means approved by a person, or seeded by a migration.
    CONSTRAINT reference_records_active_approved CHECK (
        status NOT IN ('ACTIVE', 'SUPERSEDED') OR approved_by IS NOT NULL OR created_by_type = 'SYSTEM'
    )
);

CREATE INDEX idx_reference_records_lookup ON reference_records (kind, record_key, status);
CREATE INDEX idx_reference_records_status ON reference_records (kind, status, valid_from);

-- Idempotency-Key of a reference command, stored with the state change it produced.
CREATE TABLE reference_commands (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    action          VARCHAR(32)  NOT NULL,
    kind            VARCHAR(32)  NOT NULL,
    record_key      VARCHAR(128) NOT NULL,
    version         INT          NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);

ALTER TABLE counterparties DROP CONSTRAINT IF EXISTS counterparties_kind_check;
ALTER TABLE counterparties ADD CONSTRAINT counterparties_kind_check
    CHECK (kind IN ('BANK', 'CENTRAL_BANK', 'CORPORATE', 'ISSUER'));

-- Seeds. valid_from 2000-01-01 so every existing deal's trade date is covered.
INSERT INTO reference_records (id, kind, record_key, version, status, valid_from, verified, payload,
                               created_by, created_by_type, created_at, reason)
SELECT nextval('reference_records_seq'), s.kind, s.record_key, 1, 'ACTIVE', DATE '2000-01-01', s.verified,
       s.payload, 'system:seed', 'SYSTEM', now(), 'seeded by V15 (ADR-0344 D3)'
FROM (VALUES
    -- Calendars: the kernel's CERTIS (Czech public holidays) and TARGET2 closing days.
    ('calendar', 'CERTIS',  TRUE, '{"code":"CERTIS","baseCalendar":"CERTIS","extraHolidays":[],"removedHolidays":[]}'),
    ('calendar', 'TARGET2', TRUE, '{"code":"TARGET2","baseCalendar":"TARGET2","extraHolidays":[],"removedHolidays":[]}'),
    -- Indices: market-standard conventions, not cited from the administrators' rulebooks here.
    ('index', 'PRIBOR_1M', FALSE, '{"code":"PRIBOR_1M","currency":"CZK","tenor":"1M","dayCount":"ACT/360","fixingLagBusinessDays":2,"fixingCalendar":"CERTIS","source":"FEED","staleAfterBusinessDays":1,"suspiciousJumpBp":50}'),
    ('index', 'PRIBOR_3M', FALSE, '{"code":"PRIBOR_3M","currency":"CZK","tenor":"3M","dayCount":"ACT/360","fixingLagBusinessDays":2,"fixingCalendar":"CERTIS","source":"FEED","staleAfterBusinessDays":1,"suspiciousJumpBp":50}'),
    ('index', 'PRIBOR_6M', FALSE, '{"code":"PRIBOR_6M","currency":"CZK","tenor":"6M","dayCount":"ACT/360","fixingLagBusinessDays":2,"fixingCalendar":"CERTIS","source":"FEED","staleAfterBusinessDays":1,"suspiciousJumpBp":50}'),
    ('index', 'CZEONIA',   FALSE, '{"code":"CZEONIA","currency":"CZK","tenor":"ON","dayCount":"ACT/360","fixingLagBusinessDays":0,"fixingCalendar":"CERTIS","source":"CNB","staleAfterBusinessDays":1,"suspiciousJumpBp":50}'),
    ('index', 'ESTR',      FALSE, '{"code":"ESTR","currency":"EUR","tenor":"ON","dayCount":"ACT/360","fixingLagBusinessDays":0,"fixingCalendar":"TARGET2","source":"ECB","staleAfterBusinessDays":1,"suspiciousJumpBp":50}'),
    ('index', 'EURIBOR_3M', FALSE, '{"code":"EURIBOR_3M","currency":"EUR","tenor":"3M","dayCount":"ACT/360","fixingLagBusinessDays":2,"fixingCalendar":"TARGET2","source":"FEED","staleAfterBusinessDays":1,"suspiciousJumpBp":50}'),
    ('index', 'EURIBOR_6M', FALSE, '{"code":"EURIBOR_6M","currency":"EUR","tenor":"6M","dayCount":"ACT/360","fixingLagBusinessDays":2,"fixingCalendar":"TARGET2","source":"FEED","staleAfterBusinessDays":1,"suspiciousJumpBp":50}'),
    -- Books: one banking book at amortised cost, one trading book at FVTPL (IFRS 9 4.1).
    ('book', 'BANKING-BOOK', FALSE, '{"id":"BANKING-BOOK","parentId":null,"name":"Treasury banking book","purpose":"BANKING_BOOK","ifrs9Category":"AMORTISED_COST"}'),
    ('book', 'TRADING-BOOK', FALSE, '{"id":"TRADING-BOOK","parentId":null,"name":"Treasury trading book","purpose":"TRADING_BOOK","ifrs9Category":"FVTPL"}'),
    -- Bucket set: contiguous calendar-day buckets from 0.
    ('bucket-set', 'STANDARD', FALSE, '{"id":"STANDARD","name":"Standard maturity buckets","buckets":[{"label":"O/N","fromDays":0,"toDays":1},{"label":"1W","fromDays":1,"toDays":7},{"label":"1M","fromDays":7,"toDays":30},{"label":"3M","fromDays":30,"toDays":91},{"label":"6M","fromDays":91,"toDays":182},{"label":"1Y","fromDays":182,"toDays":365},{"label":"2Y","fromDays":365,"toDays":730},{"label":"5Y","fromDays":730,"toDays":1826},{"label":"10Y","fromDays":1826,"toDays":3652},{"label":">10Y","fromDays":3652,"toDays":null}]}')
) AS s (kind, record_key, verified, payload);

-- The existing counterparty master becomes version 1 of each counterparty record. Country and
-- LEI were never captured: the country is assumed CZ and the record left unverified.
INSERT INTO reference_records (id, kind, record_key, version, status, valid_from, verified, payload,
                               created_by, created_by_type, created_at, reason)
SELECT nextval('reference_records_seq'), 'counterparty', c.counterparty_id, 1, 'ACTIVE', DATE '2000-01-01', FALSE,
       json_build_object(
           'id', c.counterparty_id,
           'name', c.name,
           'kind', c.kind,
           'lei', NULL,
           'countryCode', 'CZ',
           'limits', json_strip_nulls(json_build_object('CZK', c.limit_czk, 'EUR', c.limit_eur)),
           'nettingAgreementId', NULL
       )::text,
       'system:seed', 'SYSTEM', now(), 'migrated from the counterparty master by V15 (ADR-0344 D3)'
FROM counterparties c;
