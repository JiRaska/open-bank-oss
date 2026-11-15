-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0381. These tables are a Ledger-local enforcement projection. Enrollment and
-- permit publication require a separately trusted writer. This migration enrolls
-- no accounts. Roll back application wiring and retain rows as decision evidence.
-- Removing the check while an account is enrolled requires suspending postings first.

CREATE TABLE ledger_legal_posting_scope (
    account_id UUID NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    revision BIGINT NOT NULL CHECK (revision > 0),
    PRIMARY KEY (account_id, currency)
);

CREATE TABLE ledger_legal_posting_permit (
    account_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    transaction_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    intent JSONB NOT NULL CHECK (jsonb_typeof(intent) = 'object'),
    is_reversal BOOLEAN GENERATED ALWAYS AS ((intent ->> 'reversal_of') IS NOT NULL) STORED,
    valid_until TIMESTAMPTZ NOT NULL,
    verification_ref UUID NOT NULL,
    consumed_by UUID,
    PRIMARY KEY (account_id, currency, transaction_id, revision, is_reversal),
    FOREIGN KEY (account_id, currency) REFERENCES ledger_legal_posting_scope (account_id, currency)
);

-- The same key is acquired before looking for the scope, including while the
-- scope does not exist yet. A hash collision only serializes independent pockets.
CREATE UNIQUE INDEX ledger_legal_posting_claim_once
    ON ledger_legal_posting_permit (account_id, currency, transaction_id, is_reversal)
    WHERE consumed_by IS NOT NULL;

CREATE FUNCTION ledger_legal_pocket_key(account UUID, currency TEXT)
RETURNS BIGINT LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT hashtextextended(account::text || ':' || currency, 381)
$$;

CREATE FUNCTION guard_ledger_legal_posting_scope() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Legal scope cannot be deleted' USING ERRCODE = '23514';
    END IF;
    PERFORM pg_advisory_xact_lock(ledger_legal_pocket_key(NEW.account_id, NEW.currency));
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.account_id, NEW.currency) IS DISTINCT FROM (OLD.account_id, OLD.currency)
            OR NEW.revision <= OLD.revision THEN
            RAISE EXCEPTION 'Legal scope revision must advance' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ledger_legal_scope_insert_or_update
    BEFORE INSERT OR UPDATE OR DELETE ON ledger_legal_posting_scope
    FOR EACH ROW EXECUTE FUNCTION guard_ledger_legal_posting_scope();

-- Hibernate invokes this once before writing journal rows. Journal, outbox and
-- permit consumption commit together. If any pocket refuses, the caller aborts
-- the entire transaction. Keep the lock order identical for a multi-pocket writer.
CREATE FUNCTION ledger_consume_legal_posting_permits(
    posting_id UUID, payment_id UUID, posting_intent JSONB, debit_pockets JSONB
) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE
    pocket RECORD;
    current_revision BIGINT;
BEGIN
    FOR pocket IN
        SELECT p.account_id, p.currency
        FROM jsonb_to_recordset(debit_pockets) AS p(account_id UUID, currency TEXT)
        ORDER BY ledger_legal_pocket_key(p.account_id, p.currency), p.account_id, p.currency
    LOOP
        PERFORM pg_advisory_xact_lock(ledger_legal_pocket_key(pocket.account_id, pocket.currency));
        SELECT s.revision INTO current_revision
        FROM ledger_legal_posting_scope s
        WHERE s.account_id = pocket.account_id AND s.currency = pocket.currency;
        IF FOUND THEN
            UPDATE ledger_legal_posting_permit a
            SET consumed_by = posting_id
            WHERE a.account_id = pocket.account_id AND a.currency = pocket.currency
              AND a.transaction_id = payment_id AND a.revision = current_revision
              AND a.intent = posting_intent AND a.consumed_by IS NULL
              AND a.valid_until > clock_timestamp()
              AND NOT EXISTS (
                  SELECT 1 FROM ledger_legal_posting_permit used
                  WHERE used.account_id = a.account_id AND used.currency = a.currency
                    AND used.transaction_id = a.transaction_id AND used.is_reversal = a.is_reversal
                    AND used.consumed_by IS NOT NULL
              );
            IF NOT FOUND THEN
                RETURN FALSE;
            END IF;
        END IF;
    END LOOP;
    RETURN TRUE;
END;
$$;
