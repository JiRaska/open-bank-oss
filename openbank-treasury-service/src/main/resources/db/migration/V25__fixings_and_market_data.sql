-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 D5 / contract sections 4 and 8.5: index observations, their four-eyes batches, the
-- quality-gate cases that block a fixing, and the provenance a FIXED flow records.
--
-- No service in this deployment ingests the interbank reference indices, so observations arrive as a
-- bounded import (or one manual fixing), each a PENDING batch a DIFFERENT human approves; the rows
-- become ACTIVE only then. A fixing reads ACTIVE rows only, through the quality gate: a missing,
-- stale or suspicious value opens a case and blocks that index-date — it is never fixed from the
-- last value.
--
-- Rollback: DROP TABLE fixing_cases, index_observations, observation_batches; DROP the three
-- deal_cash_flows columns; restore the reference kind CHECK of V22.

CREATE TABLE observation_batches (
    batch_id        UUID          PRIMARY KEY,
    kind            VARCHAR(16)   NOT NULL CHECK (kind IN ('IMPORT', 'MANUAL_FIXING', 'FEED')),
    status          VARCHAR(16)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    row_count       INT           NOT NULL CHECK (row_count BETWEEN 1 AND 5000),
    reason          VARCHAR(1000) NOT NULL,
    file_sha256     CHAR(64),
    created_by      VARCHAR(255)  NOT NULL,
    created_by_type VARCHAR(16)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    decided_by      VARCHAR(255),
    decided_by_type VARCHAR(16),
    decided_at      TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    -- four-eyes in the database too: the decider is a different human
    CONSTRAINT observation_batches_four_eyes CHECK (decided_by IS NULL OR decided_by <> created_by),
    CONSTRAINT observation_batches_decider_human CHECK (decided_by_type IS NULL OR decided_by_type = 'HUMAN')
);
CREATE INDEX idx_observation_batches_status ON observation_batches (status, created_at);

CREATE SEQUENCE IF NOT EXISTS index_observations_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE index_observations (
    id               BIGINT        PRIMARY KEY,
    batch_id         UUID          NOT NULL REFERENCES observation_batches (batch_id),
    index_code       VARCHAR(32)   NOT NULL,
    observation_date DATE          NOT NULL,
    value_percent    NUMERIC(16,8) NOT NULL CHECK (value_percent BETWEEN -100 AND 100),
    source           VARCHAR(16)   NOT NULL CHECK (source IN ('IMPORT', 'MANUAL', 'FEED')),
    status           VARCHAR(16)   NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED')),
    sha256           CHAR(64)      NOT NULL,
    CONSTRAINT index_observations_batch_slot UNIQUE (batch_id, index_code, observation_date)
);
-- At most one ACTIVE value per index and date: a correction is a new batch that supersedes nothing
-- silently — approving it while another value is ACTIVE fails, and the old one must be rejected first.
CREATE UNIQUE INDEX uq_index_observations_active ON index_observations (index_code, observation_date)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_index_observations_lookup ON index_observations (index_code, observation_date, status);

CREATE SEQUENCE IF NOT EXISTS fixing_cases_seq START WITH 1 INCREMENT BY 50;
CREATE TABLE fixing_cases (
    id           BIGINT        PRIMARY KEY,
    index_code   VARCHAR(32)   NOT NULL,
    fixing_date  DATE          NOT NULL,
    status       VARCHAR(16)   NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    reason       VARCHAR(500)  NOT NULL,
    value_percent    NUMERIC(16,8),
    previous_percent NUMERIC(16,8),
    stale_days   INT,
    jump_bp      INT,
    missing      BOOLEAN       NOT NULL,
    opened_at    TIMESTAMPTZ   NOT NULL,
    resolved_at  TIMESTAMPTZ,
    CONSTRAINT fixing_cases_slot UNIQUE (index_code, fixing_date)
);
CREATE INDEX idx_fixing_cases_status ON fixing_cases (status, fixing_date);

ALTER TABLE deal_cash_flows ADD COLUMN fixing_source VARCHAR(16)
    CHECK (fixing_source IS NULL OR fixing_source IN ('IMPORT', 'MANUAL', 'FEED'));
ALTER TABLE deal_cash_flows ADD COLUMN fixing_sha256 CHAR(64);
ALTER TABLE deal_cash_flows ADD COLUMN fixed_at TIMESTAMPTZ;
ALTER TABLE deal_cash_flows ADD CONSTRAINT deal_cash_flows_observed_provenance
    CHECK (rate_source <> 'OBSERVED' OR state <> 'FIXED' OR fixing_sha256 IS NOT NULL OR fixed_at IS NULL);

-- Internal (bank-published) rate sheets are an effective-dated, four-eyes reference kind.
ALTER TABLE reference_records DROP CONSTRAINT IF EXISTS reference_records_kind_check;
ALTER TABLE reference_records ADD CONSTRAINT reference_records_kind_check CHECK (kind IN (
    'calendar', 'index', 'counterparty', 'ssi', 'netting', 'security', 'book', 'bucket-set',
    'regulatory-code', 'product-type', 'party-group', 'party-type', 'country', 'document-type',
    'account', 'settlement-place', 'loan-type', 'communication-channel', 'market-factor',
    'own-capital-item', 'period-length', 'scenario', 'behavioural-scenario', 'calculation-settings',
    'limit-settings', 'derivative-conversion-factor', 'collateral-haircut', 'portfolio-group',
    'accounting-settings', 'balance-sheet-side', 'dealing-filter', 'internal-rate-sheet'));
