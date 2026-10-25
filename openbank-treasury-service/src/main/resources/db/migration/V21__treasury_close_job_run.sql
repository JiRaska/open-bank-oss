-- SPDX-License-Identifier: Apache-2.0
-- ADR-0342 D1 / #13167: one row per (accounting day, scheduled job) recording that treasury's job
-- ran for that Prague accounting day and what it did. TreasuryCloseParticipationScheduler folds these
-- rows into the day-close participation report treasury-service sends to ledger-service; a due job
-- with no completed row means the day is NOT reported, so the ledger keeps it in CUTOFF instead of
-- closing it without treasury.
-- The accrual runs on an interval (hourly), so it runs many times a day: items ACCUMULATE across the
-- day's passes, while refused/refusal_reason are the LATEST pass's, so a pass that succeeds after a
-- refusal releases the day and one that is refused again keeps holding it. journals_posted is kept
-- for the shape shared with interest and lending; the reporter takes the day's journal count from
-- deal_journals (treasury's own record of every journal the ledger acknowledged), not from here.
-- `running` is the in-flight marker: set (with started_at) before a pass does any work, cleared when
-- it records its result. While it stands the day is not reported; one older than the reporter's
-- DERIVED stale threshold (TreasuryCloseJobs.staleAfter: pass budget + margin) is reported REFUSED
-- (the pass started and never finished). completed_at is NULL until a pass for the row completes.
-- Rollback: revert the feature and retain this additive table and its past-run evidence (DROP TABLE
-- treasury_close_job_run only under a separately reviewed retention plan: missing evidence delays
-- day-close reporting, and future runs cannot reconstruct it).
CREATE TABLE treasury_close_job_run (
    business_date   DATE          NOT NULL,
    job             VARCHAR(32)   NOT NULL,
    items_processed INTEGER       NOT NULL DEFAULT 0 CHECK (items_processed >= 0),
    journals_posted INTEGER       NOT NULL DEFAULT 0 CHECK (journals_posted >= 0),
    refused         INTEGER       NOT NULL DEFAULT 0 CHECK (refused >= 0),
    refusal_reason  VARCHAR(1000),
    running         BOOLEAN       NOT NULL DEFAULT FALSE,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    PRIMARY KEY (business_date, job)
);

-- The reporter counts, per Prague accounting day, the journals treasury recorded in deal_journals
-- (posted_at in that day). deal_journals had no index on posted_at; a plain build at boot holds a
-- SHARE lock on a table that grows by a few rows per deal, so it costs one short scan.
-- Rollback: DROP INDEX IF EXISTS idx_deal_journals_posted_at; (queries stay correct, only slower).
CREATE INDEX IF NOT EXISTS idx_deal_journals_posted_at ON deal_journals (posted_at);
