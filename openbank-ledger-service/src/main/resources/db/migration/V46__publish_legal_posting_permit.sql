-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- ADR-0381. Rollback: stop the issuer before reverting it. Retain issued permits as evidence.

ALTER TABLE ledger_legal_posting_permit
    ADD COLUMN reservation_ref UUID,
    ADD COLUMN verified_actor UUID;

CREATE UNIQUE INDEX ledger_legal_reservation_used_once
    ON ledger_legal_posting_permit (reservation_ref)
    WHERE reservation_ref IS NOT NULL;

-- Trusted in-process issuance. An old or absent projection cannot admit a debit.
-- The caller establishes the current verified legal decision and the funded hold.
CREATE FUNCTION ledger_publish_legal_posting_permit(
    pocket UUID, ccy TEXT, payment UUID, expected_revision BIGINT, posting_intent JSONB,
    expires TIMESTAMPTZ, verification UUID, reservation UUID, actor UUID
) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(ledger_legal_pocket_key(pocket, ccy));
    IF expires IS NULL OR verification IS NULL OR reservation IS NULL OR actor IS NULL
        OR expires <= clock_timestamp() OR NOT EXISTS (
            SELECT 1 FROM ledger_legal_posting_scope
            WHERE account_id = pocket AND currency = ccy AND revision = expected_revision
        ) OR EXISTS (
            SELECT 1 FROM ledger_legal_posting_permit
            WHERE account_id = pocket AND currency = ccy AND transaction_id = payment
              AND NOT is_reversal AND consumed_by IS NOT NULL
        ) THEN
        RETURN FALSE;
    END IF;

    INSERT INTO ledger_legal_posting_permit
        (account_id, currency, transaction_id, revision, intent, valid_until,
         verification_ref, reservation_ref, verified_actor)
    VALUES (pocket, ccy, payment, expected_revision, posting_intent, expires, verification, reservation, actor)
    ON CONFLICT DO NOTHING;

    RETURN EXISTS (
        SELECT 1 FROM ledger_legal_posting_permit
        WHERE account_id = pocket AND currency = ccy AND transaction_id = payment
          AND revision = expected_revision AND intent = posting_intent AND valid_until = expires
          AND verification_ref = verification AND reservation_ref = reservation AND verified_actor = actor
          AND consumed_by IS NULL
    );
END;
$$;

-- Revisions describe entire verified pocket snapshots. Older deliveries cannot
-- roll back newer facts. A repeated delivery of the same revision is a no-op.
CREATE FUNCTION ledger_apply_legal_pocket_revision(pocket UUID, ccy TEXT, next_revision BIGINT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    IF next_revision IS NULL OR next_revision <= 0 THEN
        RETURN FALSE;
    END IF;
    PERFORM pg_advisory_xact_lock(ledger_legal_pocket_key(pocket, ccy));
    INSERT INTO ledger_legal_posting_scope (account_id, currency, revision)
    VALUES (pocket, ccy, next_revision)
    ON CONFLICT (account_id, currency) DO UPDATE SET revision = EXCLUDED.revision
    WHERE ledger_legal_posting_scope.revision < EXCLUDED.revision;

    RETURN EXISTS (
        SELECT 1 FROM ledger_legal_posting_scope
        WHERE account_id = pocket AND currency = ccy AND revision = next_revision
    );
END;
$$;
