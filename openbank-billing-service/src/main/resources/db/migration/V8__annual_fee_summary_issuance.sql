-- #12187: retain the account/year issuance key after the delivery outbox payload is purged.
-- The deterministic aggregate_id encodes (accountId, calendar year) without storing either
-- value here. Backfill every existing annual-summary intent, regardless of dispatch status, so
-- a deployment cannot reissue an earlier summary immediately after this migration.
-- Rollback: first disable the annual fee-summary scheduler, then roll back the application.
-- Keep this table: an older binary checks only billing_outbox and cannot safely rerun a year
-- after its SENT row has been purged. Dropping the table would also destroy the issuance
-- history needed for a later forward deploy. Resume the scheduler only on the V8-aware binary.
CREATE TABLE billing_annual_fee_summary_issuance (
    aggregate_id UUID PRIMARY KEY,
    recorded_at TIMESTAMPTZ NOT NULL
);

INSERT INTO billing_annual_fee_summary_issuance (aggregate_id, recorded_at)
SELECT aggregate_id, MIN(created_at)
FROM billing_outbox
WHERE event_type = 'billing.annual-fee-summary.ready'
GROUP BY aggregate_id;
