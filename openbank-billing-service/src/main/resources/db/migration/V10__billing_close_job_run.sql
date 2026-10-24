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

-- A fee the ledger refused with 409 ACCOUNTING_DAY_CLOSED is FAILED and never posts on its own: the
-- day it belonged to no longer accepts postings. Recovery (runbook 0033) re-bills it into the next
-- open day under a NEW idempotency key, approved by two people. This table is that record: which fee
-- was re-billed, under which key, by whom and approved by whom. The outbox row it creates carries
-- `rebillOf` = original_key, so the dispatcher marks the ORIGINAL fee POSTED with the new journal.
-- maker <> checker is enforced here, not only by procedure.
CREATE TABLE billing_fee_rebill (
    rebill_key      VARCHAR(200)  PRIMARY KEY,
    original_key    VARCHAR(160)  NOT NULL UNIQUE REFERENCES assessed_fee (idempotency_key),
    reason          VARCHAR(200)  NOT NULL,
    change_ticket   VARCHAR(100)  NOT NULL,
    maker           VARCHAR(100)  NOT NULL,
    checker         VARCHAR(100)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    -- Compared trimmed and case-folded: "Alice " and "alice" are the same person.
    CONSTRAINT billing_fee_rebill_four_eyes CHECK (lower(btrim(maker)) <> lower(btrim(checker)))
);
