-- SPDX-License-Identifier: Apache-2.0
-- Nostro reconciliation (ADR-0315, #10896): a correspondent's camt.053 end-of-day statement and
-- its booked entries. Reconciliation itself is computed on read against the ledger and never posts.
--
-- Rollback: additive only. DROP TABLE nostro_statement_entries, nostro_statements;
-- DROP SEQUENCE nostro_statements_seq, nostro_statement_entries_seq;
-- (no other table references them).

CREATE SEQUENCE IF NOT EXISTS nostro_statements_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS nostro_statement_entries_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE nostro_statements (
    id              BIGINT PRIMARY KEY,
    statement_uuid  UUID           NOT NULL UNIQUE,
    idempotency_key VARCHAR(255)   NOT NULL,
    statement_id    VARCHAR(64)    NOT NULL,
    iban            VARCHAR(34)    NOT NULL,
    gl_code         VARCHAR(16)    NOT NULL,
    currency        CHAR(3)        NOT NULL,
    statement_date  DATE           NOT NULL,
    opening_balance NUMERIC(19, 4) NOT NULL,
    closing_balance NUMERIC(19, 4) NOT NULL,
    sha256          CHAR(64)       NOT NULL,
    uploaded_by     VARCHAR(255)   NOT NULL,
    uploaded_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_nostro_statement_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uq_nostro_statement_account UNIQUE (iban, statement_id)
);

CREATE TABLE nostro_statement_entries (
    id             BIGINT PRIMARY KEY,
    statement_uuid UUID           NOT NULL REFERENCES nostro_statements (statement_uuid),
    sequence       INT            NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency       CHAR(3)        NOT NULL,
    direction      VARCHAR(4)     NOT NULL CHECK (direction IN ('CRDT', 'DBIT')),
    booking_date   DATE           NOT NULL,
    reference      VARCHAR(255),
    CONSTRAINT uq_nostro_entry_sequence UNIQUE (statement_uuid, sequence)
);
