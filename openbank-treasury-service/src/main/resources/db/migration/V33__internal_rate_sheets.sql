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

-- The `internal-rate-sheet` reference kind (ReferenceKind.INTERNAL_RATE_SHEET, already in the API's
-- kind enum) becomes storable: the reference_records kind CHECK of V18 gains it. A plain approver
-- activates it (rego non_limit_reference_kinds); it carries no credit limit.
-- Rollback: re-add the V18 CHECK without 'internal-rate-sheet' (fails while such a record exists).
ALTER TABLE reference_records DROP CONSTRAINT IF EXISTS reference_records_kind_check;
ALTER TABLE reference_records ADD CONSTRAINT reference_records_kind_check CHECK (kind IN (
    'calendar', 'index', 'counterparty', 'ssi', 'netting', 'security', 'book', 'bucket-set',
    'regulatory-code', 'product-type', 'party-group', 'party-type', 'country', 'document-type',
    'account', 'settlement-place', 'loan-type', 'communication-channel', 'market-factor',
    'own-capital-item', 'period-length', 'scenario', 'behavioural-scenario', 'calculation-settings',
    'limit-settings', 'derivative-conversion-factor', 'collateral-haircut', 'portfolio-group',
    'accounting-settings', 'balance-sheet-side', 'dealing-filter', 'internal-rate-sheet'));
