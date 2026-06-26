-- SPDX-License-Identifier: MPL-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
-- See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
--
-- ADR-0036 — debtor-side SEPA Direct Debit mandate vault + transactional outbox.
-- Rollback: DROP TABLE sdd_outbox; DROP TABLE sdd_mandate;  (no data dependencies elsewhere).

CREATE TABLE sdd_mandate (
    id                          UUID         NOT NULL,
    account_id                  UUID         NOT NULL,
    debtor_iban                 VARCHAR(34)  NOT NULL,
    creditor_identifier         VARCHAR(35)  NOT NULL,
    umr                         VARCHAR(35)  NOT NULL,
    scheme                      VARCHAR(8)   NOT NULL,
    sequence_type               VARCHAR(8)   NOT NULL,
    creditor_name               VARCHAR(140) NOT NULL,
    debtor_name                 VARCHAR(140) NOT NULL,
    signature_date              DATE         NOT NULL,
    status                      VARCHAR(24)  NOT NULL,
    b2b_confirmed               BOOLEAN      NOT NULL DEFAULT FALSE,
    last_collection_date        DATE,
    last_pre_notification_date  DATE,
    created_at                  TIMESTAMPTZ  NOT NULL,
    amendments                  TEXT         NOT NULL DEFAULT '[]',
    CONSTRAINT pk_sdd_mandate PRIMARY KEY (id)
);

-- A mandate is identified by the rulebook pair (creditor identifier, UMR).
CREATE UNIQUE INDEX uq_sdd_mandate_reference ON sdd_mandate (creditor_identifier, umr);
CREATE INDEX ix_sdd_mandate_account ON sdd_mandate (account_id);
CREATE INDEX ix_sdd_mandate_status ON sdd_mandate (status);

CREATE TABLE sdd_outbox (
    id            UUID        NOT NULL,
    event_id      UUID        NOT NULL,
    aggregate_id  UUID        NOT NULL,
    event_type    VARCHAR(64) NOT NULL,
    payload       TEXT        NOT NULL,
    status        VARCHAR(16) NOT NULL,
    attempt_count INTEGER     NOT NULL DEFAULT 0,
    sent_at       TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_sdd_outbox PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uq_sdd_outbox_event ON sdd_outbox (event_id);
CREATE INDEX ix_sdd_outbox_status ON sdd_outbox (status, created_at);
