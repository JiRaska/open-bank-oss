-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0283 phase 3: the tables behind the network-token mirror and the dispute desk.
--
-- ROLLBACK: DROP TABLE card_dispute_cases; DROP TABLE card_network_tokens;
-- Both are new in this migration and no other table references them, so the drop is complete and
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
    -- UNIQUE, because provisioning is not naturally idempotent: asking twice mints two credentials
    -- the customer can see. This index is what makes a retried request return the first token.
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
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
