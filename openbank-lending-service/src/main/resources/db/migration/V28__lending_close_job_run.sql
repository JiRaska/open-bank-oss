-- ADR-0342 D1 / #13167: one row per (accounting day, scheduled job) recording that the job ran for
-- that day and what it did. LendingCloseParticipationScheduler folds these rows into the day-close
-- participation report lending-service sends to ledger-service; a due job with no row means the day
-- is NOT reported, so the ledger keeps the day in CUTOFF instead of closing it without lending.
-- The interval jobs can run more than once a day (pod restart): items and journals ACCUMULATE across
-- the day's runs, while refused/refusal_reason are the LATEST run's, so a re-run that succeeds after a
-- refusal releases the day and one that is refused again keeps holding it.
-- `running` is the in-flight marker: set (with started_at) before a pass does any work, cleared when it
-- records its result. While it stands the day is not reported; one older than the reporter's stale
-- threshold is reported REFUSED (the pass started and never finished). completed_at is NULL until the
-- first pass for the row completes.
-- Rollback: revert the feature and retain this additive table and past-run evidence.
-- Schema removal requires a separate reviewed retention/rollback plan; future runs cannot
-- reconstruct all historical evidence, and missing records delay day-close reporting.
CREATE TABLE lending_close_job_run (
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

-- The reporter asks, per accounting day, how many allowance journals provisioning queued for it are
-- still in the outbox. lending_outbox is exempt from SENT retention (ADR-0329), so it only grows: index
-- exactly that question instead of scanning the table every hour.
CREATE INDEX idx_lending_outbox_allowance_accounting_date
    ON lending_outbox ((CAST(payload AS jsonb) ->> 'accountingDate'))
    WHERE event_type = 'lending.allowance.posting';
