-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- Card processing: authorisations (which are also the holds) and the transactional outbox.
-- ADR-0283 phase 1 (#8809).
--
-- ROLLBACK: DROP TABLE card_outbox; DROP TABLE card_authorizations;
-- Safe while no authorisation has been taken; after that the rows are accounting records under a
-- 7-year retention (governance.yaml) and dropping them is a data-loss event, not a rollback.
--
-- NO PAN, NO CARD CREDENTIAL, BY DESIGN: the card is referenced by its card-issuance id. That is
-- what keeps this service outside the cardholder-data environment (ADR-0283 D7).

CREATE TABLE card_authorizations (
    id                          UUID PRIMARY KEY,
    card_id                     UUID         NOT NULL,
    account_id                  UUID         NOT NULL,
    party_id                    UUID         NOT NULL,
    amount_minor_units          BIGINT       NOT NULL CHECK (amount_minor_units > 0),
    currency_code               VARCHAR(3)   NOT NULL,
    channel                     VARCHAR(16)  NOT NULL,
    mcc                         VARCHAR(4),
    merchant_name               VARCHAR(140),
    merchant_country            VARCHAR(2),
    status                      VARCHAR(20)  NOT NULL,
    category                    VARCHAR(40)  NOT NULL,
    decline_reason              VARCHAR(40),
    cleared_amount_minor_units  BIGINT       NOT NULL DEFAULT 0 CHECK (cleared_amount_minor_units >= 0),
    network_reference           VARCHAR(64),
    idempotency_key             VARCHAR(128) NOT NULL,
    authorized_at               TIMESTAMPTZ  NOT NULL,
    expires_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    -- Cleared can never exceed authorised: the same invariant AuthorizationLifecycle.clear
    -- enforces, restated where no code path can get past it. A control that lives only in the
    -- application is a control any future writer can forget.
    CONSTRAINT card_authorizations_cleared_within_authorized
        CHECK (cleared_amount_minor_units <= amount_minor_units),
    -- A decline names its reason and an approval does not carry one. Without this, "approved with
    -- a decline reason" is a representable state and every reader has to decide what it means.
    CONSTRAINT card_authorizations_decline_reason_iff_declined
        CHECK ((status = 'DECLINED') = (decline_reason IS NOT NULL))
);

-- Retry safety for the acquirer: the same authorisation request must never take a second hold.
CREATE UNIQUE INDEX ux_card_authorizations_idempotency_key ON card_authorizations (idempotency_key);
-- A reversal often arrives carrying only the acquirer's own reference.
CREATE UNIQUE INDEX ux_card_authorizations_network_reference
    ON card_authorizations (network_reference) WHERE network_reference IS NOT NULL;
-- The spend counters: every authorisation for one card inside a time window.
CREATE INDEX ix_card_authorizations_card_authorized_at ON card_authorizations (card_id, authorized_at DESC);
-- The expiry sweep: only rows that still hold funds can expire, so the index carries the predicate.
CREATE INDEX ix_card_authorizations_expiring
    ON card_authorizations (expires_at) WHERE status IN ('APPROVED', 'PARTIALLY_CLEARED');

COMMENT ON TABLE card_authorizations IS
    'Card authorisations and their holds (ADR-0283 phase 1). No PAN is stored — the card is referenced by its card-issuance id.';
COMMENT ON COLUMN card_authorizations.cleared_amount_minor_units IS
    'Cumulative presented amount. The outstanding hold is amount - cleared, derived and never stored.';

-- Transactional outbox (ADR-0050). Shape matches every other outbox-bearing service, including the
-- card-only claimed_at column card-issuance added for the atomic cross-pod claim (#1201).
CREATE TABLE card_outbox (
    id             BIGSERIAL PRIMARY KEY,
    event_id       UUID         NOT NULL UNIQUE,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count  INT          NOT NULL DEFAULT 0,
    last_error     TEXT,
    synthetic      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ,
    claimed_at     TIMESTAMPTZ
);

CREATE INDEX ix_card_outbox_claim ON card_outbox (status, created_at);
CREATE INDEX ix_card_outbox_aggregate ON card_outbox (aggregate_id);
