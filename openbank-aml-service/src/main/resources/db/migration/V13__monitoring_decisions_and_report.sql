-- SPDX-License-Identifier: Apache-2.0
-- ADR-0371 slice 3: four-eyes dispositions of monitoring cases and the suspicious-transaction report.
--
-- Forward-only, additive: four new tables.
-- Rollback: DROP TABLE aml_str_movement; DROP TABLE aml_str_case; DROP TABLE aml_str;
--           DROP TABLE aml_case_disposition_proposal;
-- (nothing else references them; these are retained compliance records, so a drop loses evidence).

-- A proposed closing of one monitoring case. The CHECK is the storage half of four-eyes: no row can
-- hold a decision made by its own proposer, whatever the application does.
CREATE TABLE aml_case_disposition_proposal (
    proposal_id         UUID         PRIMARY KEY,
    case_id             UUID         NOT NULL,
    disposition         VARCHAR(16)  NOT NULL,
    outcome_vocabulary  VARCHAR(64)  NOT NULL,
    outcome_revision    INTEGER      NOT NULL,
    outcome_hash        VARCHAR(64)  NOT NULL,
    outcome_code        VARCHAR(64)  NOT NULL,
    note                TEXT,
    proposed_by         VARCHAR(128) NOT NULL,
    proposed_at         TIMESTAMPTZ  NOT NULL,
    state               VARCHAR(16)  NOT NULL,
    decided_by          VARCHAR(128),
    decided_at          TIMESTAMPTZ,
    decision_reason     TEXT,
    executed_at         TIMESTAMPTZ,
    CONSTRAINT ck_aml_disposition_kind CHECK (disposition IN ('CLEAR', 'REPORT')),
    CONSTRAINT ck_aml_disposition_state
        CHECK (state IN ('PROPOSED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'EXECUTED')),
    CONSTRAINT ck_aml_disposition_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by)
);
-- One open question per case.
CREATE UNIQUE INDEX uq_aml_disposition_open ON aml_case_disposition_proposal (case_id)
    WHERE state IN ('PROPOSED', 'APPROVED');
CREATE INDEX idx_aml_disposition_case ON aml_case_disposition_proposal (case_id, proposed_at);

CREATE TABLE aml_str (
    report_id            UUID         PRIMARY KEY,
    party_id             UUID         NOT NULL,
    state                VARCHAR(16)  NOT NULL,
    narrative            TEXT         NOT NULL,
    drafted_by           VARCHAR(128) NOT NULL,
    drafted_at           TIMESTAMPTZ  NOT NULL,
    approved_by          VARCHAR(128),
    approved_at          TIMESTAMPTZ,
    filing_reference     VARCHAR(128),
    filed_at             TIMESTAMPTZ,
    filing_recorded_by   VARCHAR(128),
    filing_recorded_at   TIMESTAMPTZ,
    withdrawn_by         VARCHAR(128),
    withdrawn_at         TIMESTAMPTZ,
    outcome_vocabulary   VARCHAR(64),
    outcome_revision     INTEGER,
    outcome_hash         VARCHAR(64),
    outcome_code         VARCHAR(64),
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_aml_str_state CHECK (state IN ('DRAFT', 'APPROVED', 'FILED', 'WITHDRAWN')),
    CONSTRAINT ck_aml_str_four_eyes CHECK (approved_by IS NULL OR approved_by <> drafted_by),
    -- FILED is unreachable in storage without an approver, a reference, an instant and an officer.
    CONSTRAINT ck_aml_str_filed CHECK (state <> 'FILED' OR (approved_by IS NOT NULL AND filing_reference IS NOT NULL
        AND filed_at IS NOT NULL AND filing_recorded_by IS NOT NULL AND outcome_code IS NOT NULL)),
    CONSTRAINT ck_aml_str_approved CHECK (state <> 'APPROVED' OR approved_by IS NOT NULL),
    CONSTRAINT ck_aml_str_withdrawn CHECK (state <> 'WITHDRAWN' OR (withdrawn_by IS NOT NULL AND outcome_code IS NOT NULL))
);
CREATE INDEX idx_aml_str_state ON aml_str (state, updated_at);
CREATE INDEX idx_aml_str_party ON aml_str (party_id);

CREATE TABLE aml_str_case (
    report_id UUID NOT NULL REFERENCES aml_str (report_id),
    case_id   UUID NOT NULL,
    PRIMARY KEY (report_id, case_id)
);

CREATE TABLE aml_str_movement (
    report_id        UUID       NOT NULL REFERENCES aml_str (report_id),
    journal_entry_id UUID       NOT NULL,
    account_id       UUID       NOT NULL,
    currency         VARCHAR(3) NOT NULL,
    PRIMARY KEY (report_id, journal_entry_id, account_id, currency)
);
