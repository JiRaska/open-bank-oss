-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- The ledger posting state of each applied clearing (#13228).
--
-- Why: the posting to transaction-service runs AFTER the clearing commits. When it ended FAILED or
-- UNKNOWN (an unreadable answer, a cut connection) nothing recorded it and nothing retried it, and a
-- replay of the clearing deliberately posts nothing - so the debit could be lost from the books with
-- only a counter and a log line to show for it.
--
-- posting_status:  PENDING  - the clearing committed; the posting has not been confirmed yet
--                  POSTED   - transaction-service confirmed the debit
--                  FAILED   - the last attempt was refused or errored
--                  UNKNOWN  - the last answer was unreadable: booked or not is not known
-- Rows are written PENDING in the SAME transaction as the clearing, so a crash between the commit and
-- the first attempt leaves a row the re-driver finds. A row is re-driven until POSTED or until
-- posting_attempts reaches the ceiling; past it the row stays FAILED/UNKNOWN and is counted by the
-- exhausted gauge that alerts - it is never dropped.
--
-- posting_next_attempt_at is both the backoff schedule and a short lease: a re-driver claims a row by
-- pushing it forward, so two pods do not post the same row at once. The re-post is safe regardless:
-- the ledger idempotency key is derived from (authorisation, clearing key) and transaction-service
-- dedupes on it.
--
-- Rows that existed before this migration are taken as POSTED (the DEFAULT): their state was never
-- recorded and cannot be recovered here; re-driving all of history would be a mass replay.
--
-- ROLLBACK: DROP INDEX ix_card_clearings_posting_due; ALTER TABLE card_clearings
--   DROP COLUMN posting_status, DROP COLUMN posting_attempts, DROP COLUMN posting_last_error,
--   DROP COLUMN posting_last_attempt_at, DROP COLUMN posting_next_attempt_at,
--   DROP COLUMN posting_transaction_id;
-- Safe at any time (additive; nothing references the columns), but it discards the only record of
-- which clearings still await their ledger posting.

ALTER TABLE card_clearings
    ADD COLUMN posting_status          VARCHAR(16)  NOT NULL DEFAULT 'POSTED',
    ADD COLUMN posting_attempts        INT          NOT NULL DEFAULT 0,
    ADD COLUMN posting_last_error      VARCHAR(512),
    ADD COLUMN posting_last_attempt_at TIMESTAMPTZ,
    ADD COLUMN posting_next_attempt_at TIMESTAMPTZ,
    ADD COLUMN posting_transaction_id  UUID,
    ADD CONSTRAINT ck_card_clearings_posting_status
        CHECK (posting_status IN ('PENDING', 'POSTED', 'FAILED', 'UNKNOWN'));

CREATE INDEX ix_card_clearings_posting_due
    ON card_clearings (posting_next_attempt_at)
    WHERE posting_status <> 'POSTED';

COMMENT ON COLUMN card_clearings.posting_status IS
    'Ledger posting state: PENDING, POSTED, FAILED or UNKNOWN. Anything but POSTED is re-driven.';
