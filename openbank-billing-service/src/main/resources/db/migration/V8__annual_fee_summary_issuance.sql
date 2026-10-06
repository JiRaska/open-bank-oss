-- #12187: retain the account/year issuance key after the delivery outbox payload is purged.
-- The deterministic aggregate_id encodes (accountId, calendar year) without storing either
-- value here. Backfill every existing annual-summary intent, regardless of dispatch status, so
-- a deployment cannot reissue an earlier summary immediately after this migration.
-- Rollback: disable SENT retention for billing before dropping this table; dropping it while
-- retention remains enabled would allow reissuing previously purged annual summaries.
-- DROP TABLE billing_annual_fee_summary_issuance;
CREATE TABLE billing_annual_fee_summary_issuance (
    aggregate_id UUID PRIMARY KEY,
    recorded_at TIMESTAMPTZ NOT NULL
);

INSERT INTO billing_annual_fee_summary_issuance (aggregate_id, recorded_at)
SELECT aggregate_id, MIN(created_at)
FROM billing_outbox
WHERE event_type = 'billing.annual-fee-summary.ready'
GROUP BY aggregate_id;
