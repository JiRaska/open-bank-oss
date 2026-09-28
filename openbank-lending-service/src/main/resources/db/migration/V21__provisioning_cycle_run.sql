-- SPDX-License-Identifier: Apache-2.0
-- Durable evidence of an IFRS 9 reporting-day pass. A process can stop after some
-- loan rows commit; the RUNNING row then survives restart and date rollover.
-- No old date is silently replayed from today's mutable lending facts.
--
-- Rollback: DROP TABLE provisioning_cycle_run; preserve an export of any non-COMPLETE
-- rows first, since dropping them loses evidence of reporting-day coverage gaps.

CREATE TABLE provisioning_cycle_run (
    period          DATE PRIMARY KEY,
    status          VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETE', 'INCOMPLETE')),
    started_at      TIMESTAMPTZ NOT NULL,
    checked_at      TIMESTAMPTZ,
    missing_loans   BIGINT CHECK (missing_loans >= 0),
    CHECK (status <> 'COMPLETE' OR (checked_at IS NOT NULL AND missing_loans = 0))
);

CREATE INDEX idx_provisioning_cycle_run_open_period
    ON provisioning_cycle_run (period) WHERE status <> 'COMPLETE';
