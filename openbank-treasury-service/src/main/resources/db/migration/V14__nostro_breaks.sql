-- SPDX-License-Identifier: Apache-2.0
-- Nostro reconciliation breaks (ADR-0315 D7): every item a reconciliation leaves unmatched — a
-- statement line or a ledger line on the nostro GL — with the day it was first seen, so it carries
-- an age in business days, and the day a later reconciliation matched it. alerted_at records the
-- single treasury.nostro.break-aged.v1 event per break. Nothing here is ever posted to the ledger.
--
-- Rollback: additive only. DROP TABLE nostro_breaks; DROP SEQUENCE nostro_breaks_seq;
-- (no other table references it; the statements it points at are untouched).

CREATE SEQUENCE IF NOT EXISTS nostro_breaks_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE nostro_breaks (
    id                 BIGINT PRIMARY KEY,
    break_uuid         UUID           NOT NULL UNIQUE,
    break_key          VARCHAR(128)   NOT NULL,
    iban               VARCHAR(34)    NOT NULL,
    gl_code            VARCHAR(16)    NOT NULL,
    currency           CHAR(3)        NOT NULL,
    side               VARCHAR(9)     NOT NULL CHECK (side IN ('STATEMENT', 'LEDGER')),
    our_side           VARCHAR(6)     NOT NULL CHECK (our_side IN ('DEBIT', 'CREDIT')),
    amount             NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    booking_date       DATE           NOT NULL,
    reference          VARCHAR(255),
    statement_uuid     UUID           NOT NULL REFERENCES nostro_statements (statement_uuid),
    statement_sequence INT,
    ledger_line_id     UUID,
    first_seen_on      DATE           NOT NULL,
    resolved_on        DATE,
    alerted_at         TIMESTAMPTZ,
    CONSTRAINT uq_nostro_break_key UNIQUE (break_key),
    CONSTRAINT ck_nostro_break_item CHECK (
        (side = 'STATEMENT' AND statement_sequence IS NOT NULL AND ledger_line_id IS NULL)
        OR (side = 'LEDGER' AND ledger_line_id IS NOT NULL AND statement_sequence IS NULL)
    ),
    CONSTRAINT ck_nostro_break_resolved CHECK (resolved_on IS NULL OR resolved_on >= first_seen_on)
);

CREATE INDEX idx_nostro_breaks_iban_open ON nostro_breaks (iban) WHERE resolved_on IS NULL;
CREATE INDEX idx_nostro_breaks_gl_booking ON nostro_breaks (gl_code, booking_date);
CREATE INDEX idx_nostro_breaks_statement ON nostro_breaks (statement_uuid);
