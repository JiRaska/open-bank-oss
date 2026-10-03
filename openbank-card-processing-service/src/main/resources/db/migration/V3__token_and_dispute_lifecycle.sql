-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0283 phase 3: the tables behind the network-token mirror and the dispute desk.
--
-- ROLLBACK: DROP TABLE card_dispute_evidence; DROP TABLE card_lifecycle_idempotency;
--           DROP TABLE card_dispute_cases; DROP TABLE card_network_tokens;
-- All four are new in this migration and no other table references them, so the drop is complete and
-- loses only rows written after it was applied. There is no data migration to undo.

-- The bank's RECORD that a network token exists. The vault belongs to the scheme; this row exists
-- so wallet provisioning is auditable years later, when the network no longer returns a token it
-- has deleted. No card credential, no cryptogram and no PAN is stored — `token_reference` is the
-- network's opaque handle and `last4` is the token's own display value.
CREATE TABLE card_network_tokens (
    id              UUID         PRIMARY KEY,
    card_id         UUID         NOT NULL,
    token_reference VARCHAR(128) NOT NULL UNIQUE,
    requestor_id    VARCHAR(64)  NOT NULL,
    requestor_label VARCHAR(128) NOT NULL,
    last4           VARCHAR(4)   NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    scheme          VARCHAR(16)  NOT NULL,
    expiry          DATE,
    -- The caller's key the row was provisioned under (or `adopted:<token_reference>` for a token the
    -- network reported that this bank never provisioned). UNIQUE as a backstop only: it is NOT what
    -- makes provisioning idempotent. A unique index on the RESULT row is checked at insert, i.e.
    -- after the network call, so two concurrent same-key requests would both mint a token before
    -- either insert failed. The guard is the reservation in card_lifecycle_idempotency, taken BEFORE
    -- the network is called.
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    provisioned_at  TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_card_network_tokens_card ON card_network_tokens (card_id);

CREATE TABLE card_dispute_cases (
    id                 UUID         PRIMARY KEY,
    authorization_id   UUID         NOT NULL REFERENCES card_authorizations (id),
    card_id            UUID         NOT NULL,
    -- Assigned by the NETWORK. A case without one would carry a respond-by date nobody is counting
    -- down, so opening fails closed rather than recording an intent — see CardDisputeService.
    network_case_id    VARCHAR(128) NOT NULL UNIQUE,
    reason_code        VARCHAR(32)  NOT NULL,
    amount_minor_units BIGINT       NOT NULL,
    currency_code      CHAR(3)      NOT NULL,
    status             VARCHAR(24)  NOT NULL,
    scheme             VARCHAR(16)  NOT NULL,
    scheme_status      VARCHAR(64)  NOT NULL,
    respond_by_date    DATE,
    evidence_reference VARCHAR(256),
    idempotency_key    VARCHAR(128) NOT NULL UNIQUE,
    opened_at          TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_card_dispute_cases_card ON card_dispute_cases (card_id, opened_at DESC);

-- One LIVE case per authorisation, enforced by the database.
--
-- The service reads for an existing live case first, which is what produces a readable refusal —
-- but a read followed by an insert is a race, and two operators pressing the button at the same
-- moment would open two chargebacks against one transaction. A partial unique index is the part
-- that actually prevents it; the application check only chooses the message.
CREATE UNIQUE INDEX ux_card_dispute_live_per_authorization
    ON card_dispute_cases (authorization_id)
    WHERE status IN ('OPEN', 'EVIDENCE_SUBMITTED');

-- Every evidence submission, APPEND-ONLY. card_dispute_cases.evidence_reference is only the latest;
-- a scheme rules on everything filed, so the bank must be able to show everything filed.
CREATE TABLE card_dispute_evidence (
    id                 UUID         PRIMARY KEY,
    dispute_id         UUID         NOT NULL REFERENCES card_dispute_cases (id),
    document_reference VARCHAR(256) NOT NULL,
    note               VARCHAR(2000),
    scheme_status      VARCHAR(64)  NOT NULL,
    submitted_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_card_dispute_evidence_dispute ON card_dispute_evidence (dispute_id, submitted_at);

-- Idempotency RESERVATIONS for the mutating calls that reach a card network (token provisioning,
-- dispute opening, evidence filing). A row is INSERTed under this primary key BEFORE the network is
-- called, so of two concurrent requests with the same key exactly one wins and calls the network;
-- the other replays the winner's result (state COMPLETED, result_id) or is answered 409 in progress
-- (state PENDING). The row is completed in the same transaction as the result row it points at.
--
-- A PENDING row does not expire. A request that died after the network answered may have minted a
-- token or opened a case; expiring the reservation would let a retry do it twice. A stuck PENDING
-- row is an operator reconciliation, found by `state = 'PENDING' AND created_at < now() - interval`.
CREATE TABLE card_lifecycle_idempotency (
    reservation_key VARCHAR(160) PRIMARY KEY,   -- '<operation>:<caller key>'
    operation       VARCHAR(32)  NOT NULL,
    fingerprint     CHAR(64)     NOT NULL,      -- SHA-256 of the request the key is bound to
    state           VARCHAR(16)  NOT NULL CHECK (state IN ('PENDING', 'COMPLETED')),
    result_id       UUID,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CHECK ((state = 'COMPLETED') = (result_id IS NOT NULL))
);

CREATE INDEX ix_card_lifecycle_idempotency_pending
    ON card_lifecycle_idempotency (created_at)
    WHERE state = 'PENDING';
