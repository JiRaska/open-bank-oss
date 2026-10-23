-- ADR-0342 D1 / #13167: one row per (accounting day, scheduled job) recording that the billing cycle
-- ran for that day and what it did. BillingCloseParticipationScheduler folds the row, together with
-- the posting status of that cycle's assessed fees, into the day-close participation report it sends
-- to ledger-service; a due cycle with no row means the day is NOT reported, so the ledger keeps the day
-- in CUTOFF instead of closing it without the month's fees.
-- Upserted on every (re-)run of the cycle for the same day, so a manual re-run updates the counts.
-- Rollback: revert the feature and retain this additive table and past-run evidence.
-- Schema removal requires a separate reviewed retention/rollback plan; future runs cannot
-- reconstruct all historical evidence, and missing records delay day-close reporting.
CREATE TABLE billing_close_job_run (
    business_date   DATE          NOT NULL,
    job             VARCHAR(32)   NOT NULL,
    items_processed INTEGER       NOT NULL CHECK (items_processed >= 0),
    journals_posted INTEGER       NOT NULL CHECK (journals_posted >= 0),
    refused         INTEGER       NOT NULL DEFAULT 0 CHECK (refused >= 0),
    refusal_reason  VARCHAR(1000),
    completed_at    TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (business_date, job)
);
