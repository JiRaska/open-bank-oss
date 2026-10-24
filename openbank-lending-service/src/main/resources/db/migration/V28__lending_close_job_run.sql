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
    unreconciled    BOOLEAN       NOT NULL DEFAULT FALSE,
    running         BOOLEAN       NOT NULL DEFAULT FALSE,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    PRIMARY KEY (business_date, job)
);

-- The reporter asks, per accounting day, how many allowance journals provisioning queued for it are
-- still in the outbox. lending_outbox is exempt from SENT retention (ADR-0329), so it only grows: index
-- exactly that question instead of scanning the table every hour.
--
-- Plain CREATE INDEX, not CONCURRENTLY, deliberately (#13708 review):
--   * Flyway runs this at boot inside a transaction, and CONCURRENTLY cannot run in one. Split into a
--     non-transactional migration, it must still wait for every open transaction on the table to end,
--     and at boot the service's own pools are connecting at that moment: account-service measured
--     exactly that race cancelling a CONCURRENTLY boot build (its V10 header). It would fail the deploy.
--   * The plain build holds a SHARE lock: outbox WRITES on lending_outbox wait for the build, reads and
--     the rest of the service do not. Every lending outbox write is the tail of a business transaction
--     that already tolerates the dispatcher's own seconds-long backlog, and the build reads each row
--     once (a partial index over one event type, one text key), so it costs one sequential scan of the
--     table: seconds for hundreds of thousands of rows. The table's live row count was NOT measured for
--     this change; the sandbox book is small.
--   * If the table is ever large enough for that scan to matter, build it out of band first, then this
--     migration is a no-op (IF NOT EXISTS):
--         CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_lending_outbox_allowance_accounting_date
--             ON lending_outbox ((CAST(payload AS jsonb) ->> 'accountingDate'))
--             WHERE event_type = 'lending.allowance.posting';
-- Rollback: DROP INDEX IF EXISTS idx_lending_outbox_allowance_accounting_date; (queries stay correct,
-- only slower).
CREATE INDEX IF NOT EXISTS idx_lending_outbox_allowance_accounting_date
    ON lending_outbox ((CAST(payload AS jsonb) ->> 'accountingDate'))
    WHERE event_type = 'lending.allowance.posting';


-- Persist the complete dated request before contacting ledger. Retries replay these exact bytes;
-- completion, installment status, outbox and day counters commit in one local transaction.
-- This migration is not released yet. Rollback retains pending intents: deleting them cannot
-- establish whether an acknowledged remote journal committed. No new personal-data retention applies.
CREATE TABLE lending_accrual_progress (
    installment_id UUID PRIMARY KEY REFERENCES installment(id) ON DELETE CASCADE,
    loan_id UUID NOT NULL,
    installment_number INTEGER NOT NULL,
    due_date DATE NOT NULL,
    interest_amount NUMERIC(20,2) NOT NULL CHECK (interest_amount >= 0),
    currency CHAR(3) NOT NULL,
    business_date DATE NOT NULL,
    reference TEXT NOT NULL UNIQUE,
    transaction_id UUID NOT NULL,
    requested_date DATE,
    request_json TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    journal_id UUID UNIQUE,
    booked_date DATE,
    completed_at TIMESTAMPTZ,
    CHECK ((interest_amount = 0 AND requested_date IS NULL AND request_json IS NULL)
        OR (interest_amount > 0 AND requested_date IS NOT NULL AND request_json IS NOT NULL)),
    CHECK ((journal_id IS NULL AND booked_date IS NULL)
        OR (journal_id IS NOT NULL AND booked_date IS NOT NULL AND booked_date = requested_date AND completed_at IS NOT NULL)),
    CHECK (completed_at IS NULL OR interest_amount = 0 OR journal_id IS NOT NULL)
);

-- Erasure follows the installment lifecycle. An unresolved remote outcome leaves only scoped,
-- non-identifying aggregate uncertainty; later successful passes must not clear that uncertainty.
CREATE FUNCTION refuse_erased_accrual_progress() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE affected_date DATE;
BEGIN
    IF OLD.completed_at IS NULL THEN
        FOR affected_date IN SELECT DISTINCT d FROM unnest(
            ARRAY[OLD.business_date, OLD.requested_date]) AS dates(d) WHERE d IS NOT NULL ORDER BY d
        LOOP
            INSERT INTO lending_close_job_run
                (business_date, job, refused, refusal_reason, unreconciled)
            VALUES (affected_date, 'accrual', 1,
                'accrual evidence requires reconciliation', TRUE)
            ON CONFLICT (business_date, job) DO UPDATE SET
                unreconciled = TRUE,
                refused = GREATEST(lending_close_job_run.refused, 1),
                refusal_reason = 'accrual evidence requires reconciliation';
        END LOOP;
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER refuse_erased_accrual_progress BEFORE DELETE ON lending_accrual_progress
    FOR EACH ROW EXECUTE FUNCTION refuse_erased_accrual_progress();
