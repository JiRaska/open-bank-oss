-- ADR-0248 / issue #12187: annual summary idempotency outlives billing_outbox rows.
-- The registry is intentionally retained after publication; the annual summary event is a
-- regulatory-document trigger and a rerun must not re-issue it after SENT-row retention.
--
-- Backfill every existing annual-summary outbox row, regardless of status. A PENDING/FAILED/DEAD
-- row already reserved this account/year under the previous code too. Parse and validate the
-- exact payload keys before inserting; malformed rows or duplicate account/year evidence abort
-- the migration rather than choosing one event and hiding a prior duplicate issuance.
--
-- Rollback note: stop the annual scheduler and roll back application code first. Keep this table
-- while any annual summary has been emitted or any outbox row may be purged; dropping it would
-- discard the only durable rerun guard. A lossless rollback after SENT-row purge is impossible.

CREATE TABLE billing_annual_fee_summary_issuance (
    account_id      VARCHAR(64) NOT NULL,
    calendar_year   INTEGER NOT NULL CHECK (calendar_year BETWEEN 1 AND 9999),
    source_event_id UUID NOT NULL UNIQUE,
    reserved_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_billing_annual_fee_summary_issuance PRIMARY KEY (account_id, calendar_year)
);

-- A JSON cast failure is intentionally fatal. Keep the staging relation bounded to this one
-- event type so unrelated fee payloads are never interpreted as summary payloads.
CREATE TEMPORARY TABLE billing_annual_fee_summary_backfill ON COMMIT DROP AS
SELECT event_id, payload::JSONB AS body, created_at
FROM billing_outbox
WHERE event_type = 'billing.annual-fee-summary.ready';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM billing_annual_fee_summary_backfill
        WHERE jsonb_typeof(body) <> 'object'
           OR body ->> 'eventType' IS DISTINCT FROM 'AnnualFeeSummaryReady'
           OR jsonb_typeof(body -> 'accountId') <> 'string'
           OR COALESCE(body ->> 'accountId', '') = ''
           OR jsonb_typeof(body -> 'year') IS DISTINCT FROM 'number'
           OR COALESCE(body ->> 'year', '') !~ '^[1-9][0-9]{0,3}$'
    ) THEN
        RAISE EXCEPTION 'billing annual-summary outbox contains malformed issuance identity; investigate before migrating';
    END IF;

    IF EXISTS (
        SELECT body ->> 'accountId', (body ->> 'year')::INTEGER
        FROM billing_annual_fee_summary_backfill
        GROUP BY body ->> 'accountId', (body ->> 'year')::INTEGER
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'billing annual-summary outbox has duplicate account/year issuances; investigate before migrating';
    END IF;
END $$;

INSERT INTO billing_annual_fee_summary_issuance (account_id, calendar_year, source_event_id, reserved_at)
SELECT body ->> 'accountId', (body ->> 'year')::INTEGER, event_id, created_at
FROM billing_annual_fee_summary_backfill;

-- Verify the backfill set is exactly represented. This also makes an unexpected change to the
-- selected historical rows fail closed rather than allowing startup with a partially migrated key set.
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM billing_annual_fee_summary_issuance) <>
       (SELECT COUNT(*) FROM billing_annual_fee_summary_backfill) THEN
        RAISE EXCEPTION 'billing annual-summary issuance backfill count mismatch';
    END IF;
END $$;
