-- SPDX-License-Identifier: Apache-2.0
-- Contract 8.5: internal rate sheets the bank publishes, effective-dated and four-eyes. A sheet is
-- PENDING until a different human approves it (ACTIVE) or rejects it; approving supersedes the
-- ACTIVE sheet of the same name in the same transaction. Rates are stored as their JSON list.
--
-- Rollback (no sheet ever approved): DROP TABLE internal_rate_sheets;
CREATE TABLE internal_rate_sheets (
    sheet_id         UUID          PRIMARY KEY,
    name             VARCHAR(100)  NOT NULL,
    effective_from   DATE          NOT NULL,
    rates_json       TEXT          NOT NULL,
    status           VARCHAR(12)   NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'SUPERSEDED')),
    proposed_by      VARCHAR(255)  NOT NULL,
    proposed_by_type VARCHAR(16)   NOT NULL,
    proposed_at      TIMESTAMPTZ   NOT NULL,
    decided_by       VARCHAR(255),
    decided_at       TIMESTAMPTZ,
    decision_reason  VARCHAR(500),
    row_version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT internal_rate_sheets_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by)
);
CREATE INDEX idx_internal_rate_sheets_name ON internal_rate_sheets (name, status);
CREATE UNIQUE INDEX uq_internal_rate_sheets_one_active ON internal_rate_sheets (name) WHERE status = 'ACTIVE';
