-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- One row per APPLIED clearing presentment, keyed by the acquirer's clearing key.
--
-- Why: a clearing request carried an Idempotency-Key that nothing ever looked up, so a repeated
-- presentment of the same clearing was applied again whenever it still fitted inside the remaining
-- hold — a second hold decrement and a second debit of the cardholder. Only the downstream ledger
-- posting was keyed. The authorisation row already carries its own key under a UNIQUE index; a
-- clearing is a child of the authorisation (there can be several partial ones), so its key needs a
-- row of its own.
--
-- The UNIQUE constraint is the guarantee, not the application lookup in front of it: two
-- concurrent duplicates both miss the lookup, and the second INSERT then blocks on the index until
-- the first commits and fails with 23505 — rolling back its hold decrement and its outbox event in
-- the same transaction. The service turns that failure into a replay of the winner.
--
-- request_fingerprint is the libs RequestFingerprint (SHA-256 hex) of the presented amount and
-- currency: the same key with a different body is refused as reuse (409), never replayed.
--
-- ROLLBACK: DROP TABLE card_clearings;
-- Safe at any time for the schema (nothing else references the table), but dropping it re-opens
-- the double-presentment defect: the rows are the only record of which clearing keys were applied.

CREATE TABLE card_clearings (
    id                   UUID         PRIMARY KEY,
    authorization_id     UUID         NOT NULL REFERENCES card_authorizations (id),
    idempotency_key      VARCHAR(128) NOT NULL,
    request_fingerprint  VARCHAR(64)  NOT NULL,
    amount_minor_units   BIGINT       NOT NULL CHECK (amount_minor_units > 0),
    currency_code        VARCHAR(3)   NOT NULL,
    applied_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_card_clearings_authorization_key UNIQUE (authorization_id, idempotency_key)
);

COMMENT ON TABLE card_clearings IS
    'Applied clearing presentments, one per (authorisation, clearing key). The UNIQUE constraint makes a repeated presentment impossible to apply twice.';
