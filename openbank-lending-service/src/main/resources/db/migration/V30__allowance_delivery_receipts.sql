-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- Retain actual allowance delivery acknowledgements independently of the disposable outbox.
-- Surviving SENT rows are evidence; deliveries already purged before this cutover are unknown.
-- Rollback: disable the producer; retain receipts, coverage and the trigger. Do not drop
-- financial delivery history or disable SENT retention to roll back application code.

-- Freeze writers and retention while establishing coverage and archiving the surviving evidence.
LOCK TABLE loan, lending_outbox, lending_close_job_run IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE lending_allowance_delivery_receipts (
    event_id        UUID PRIMARY KEY,
    accounting_date DATE NOT NULL,
    synthetic       BOOLEAN NOT NULL,
    sent_at         TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_lending_allowance_receipts_date ON lending_allowance_delivery_receipts(accounting_date);
-- The wider bounded audit window must not scan the entire delivery buffer once per date.
CREATE INDEX idx_lending_allowance_outbox_date_status
    ON lending_outbox ((CAST(payload AS jsonb) ->> 'accountingDate'), status)
    WHERE event_type = 'lending.allowance.posting';

CREATE TABLE lending_allowance_receipt_coverage (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    starts_on DATE NOT NULL
);
INSERT INTO lending_allowance_receipt_coverage (singleton, starts_on)
SELECT true, CASE
    WHEN NOT EXISTS (SELECT 1 FROM loan)
        AND NOT EXISTS (SELECT 1 FROM lending_outbox)
        AND NOT EXISTS (SELECT 1 FROM lending_close_job_run)
    THEN DATE '0001-01-01'
    ELSE (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Prague')::date + 1
END;

CREATE FUNCTION record_lending_allowance_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO lending_allowance_delivery_receipts (event_id, accounting_date, synthetic, sent_at)
    VALUES (NEW.event_id, (NEW.payload::jsonb ->> 'accountingDate')::date, NEW.synthetic, NEW.sent_at)
    ON CONFLICT (event_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER record_lending_allowance_delivery
AFTER INSERT OR UPDATE OF status ON lending_outbox
FOR EACH ROW
WHEN (NEW.status = 'SENT' AND NEW.event_type = 'lending.allowance.posting')
EXECUTE FUNCTION record_lending_allowance_delivery();

INSERT INTO lending_allowance_delivery_receipts (event_id, accounting_date, synthetic, sent_at)
SELECT event_id, (payload::jsonb ->> 'accountingDate')::date, synthetic, sent_at
FROM lending_outbox
WHERE event_type = 'lending.allowance.posting' AND status = 'SENT' AND sent_at IS NOT NULL
ON CONFLICT (event_id) DO NOTHING;
