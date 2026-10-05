-- SPDX-License-Identifier: Apache-2.0
-- Preserve each provisioning attempt across same-date retries and process restarts.
-- The reporting-day summary remains the alert source; events are append-only audit evidence.
--
-- Rollback: export provisioning_cycle_attempt first, then DROP TABLE provisioning_cycle_attempt
-- and ALTER TABLE provisioning_cycle_run DROP COLUMN current_attempt_id. This loses attempt
-- history, so retain the export with the reconciliation case.

ALTER TABLE provisioning_cycle_run ADD COLUMN current_attempt_id UUID;

CREATE TABLE provisioning_cycle_attempt (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    period         DATE NOT NULL REFERENCES provisioning_cycle_run(period),
    attempt_id     UUID NOT NULL,
    event_type     VARCHAR(16) NOT NULL CHECK (event_type IN ('STARTED', 'COMPLETE', 'INCOMPLETE')),
    occurred_at    TIMESTAMPTZ NOT NULL,
    missing_loans  BIGINT CHECK (missing_loans >= 0),
    CHECK (event_type <> 'STARTED' OR missing_loans IS NULL),
    CHECK (event_type <> 'COMPLETE' OR missing_loans = 0)
);

CREATE UNIQUE INDEX idx_provisioning_attempt_start
    ON provisioning_cycle_attempt(attempt_id) WHERE event_type = 'STARTED';
CREATE UNIQUE INDEX idx_provisioning_attempt_result
    ON provisioning_cycle_attempt(attempt_id) WHERE event_type <> 'STARTED';
CREATE INDEX idx_provisioning_attempt_period
    ON provisioning_cycle_attempt(period, id);

-- The older summary rows predate attempt IDs. Copy only the surviving start and
-- result facts; earlier same-day retries cannot be reconstructed.
WITH legacy AS MATERIALIZED (
    SELECT period, status, started_at, checked_at, missing_loans, gen_random_uuid() AS attempt_id
    FROM provisioning_cycle_run
    WHERE started_at IS NOT NULL
)
INSERT INTO provisioning_cycle_attempt (period, attempt_id, event_type, occurred_at, missing_loans)
SELECT period, attempt_id, 'STARTED', started_at, NULL FROM legacy
UNION ALL
SELECT period, attempt_id, status, checked_at, missing_loans
FROM legacy WHERE status IN ('COMPLETE', 'INCOMPLETE') AND checked_at IS NOT NULL;
