-- SPDX-License-Identifier: Apache-2.0
-- Portfolio statements of holdings (ADR-0337 amendment 2026-10-10): the custodian's period-end
-- ISO 20022 semt.002, the source of record for ČNB PSP 34-12 PS. Holdings per (entity, statement
-- date, ISIN) with quantity, valuation, currency and the instrument class mapped from the CFI.
--
-- A corrected statement for the same date is a NEW version: the prior row is kept and only gains
-- superseded_by / superseded_at, so every figure ever served traces to the bytes (sha256) and the
-- person (uploaded_by) it came from. At most one CURRENT version per (entity, statement_date).
--
-- Rollback: additive only. DROP TABLE portfolio_holdings, portfolio_statements;
-- DROP SEQUENCE portfolio_statements_seq, portfolio_holdings_seq;
-- (no other table references them).

CREATE SEQUENCE IF NOT EXISTS portfolio_statements_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS portfolio_holdings_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE portfolio_statements (
    id                  BIGINT PRIMARY KEY,
    statement_uuid      UUID         NOT NULL UNIQUE,
    idempotency_key     VARCHAR(255) NOT NULL,
    entity              VARCHAR(64)  NOT NULL,
    statement_id        VARCHAR(64),
    safekeeping_account VARCHAR(64)  NOT NULL,
    statement_date      DATE         NOT NULL,
    currency            CHAR(3)      NOT NULL,
    version             INT          NOT NULL CHECK (version >= 1),
    supersedes          UUID REFERENCES portfolio_statements (statement_uuid),
    superseded_by       UUID REFERENCES portfolio_statements (statement_uuid) DEFERRABLE INITIALLY DEFERRED,
    superseded_at       TIMESTAMPTZ,
    sha256              CHAR(64)     NOT NULL,
    uploaded_by         VARCHAR(255) NOT NULL,
    uploaded_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_portfolio_statement_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uq_portfolio_statement_version UNIQUE (entity, statement_date, version),
    CONSTRAINT ck_portfolio_statement_superseded CHECK ((superseded_by IS NULL) = (superseded_at IS NULL))
);

CREATE UNIQUE INDEX uq_portfolio_statement_current
    ON portfolio_statements (entity, statement_date) WHERE superseded_by IS NULL;

CREATE TABLE portfolio_holdings (
    id                 BIGINT PRIMARY KEY,
    statement_uuid     UUID           NOT NULL REFERENCES portfolio_statements (statement_uuid),
    entity             VARCHAR(64)    NOT NULL,
    statement_date     DATE           NOT NULL,
    isin               CHAR(12)       NOT NULL,
    cfi                CHAR(6)        NOT NULL,
    instrument_class   VARCHAR(64)    NOT NULL,
    quantity           NUMERIC(24, 6) NOT NULL CHECK (quantity > 0),
    valuation          NUMERIC(19, 4) NOT NULL CHECK (valuation >= 0),
    valuation_currency CHAR(3)        NOT NULL,
    CONSTRAINT uq_portfolio_holding_isin UNIQUE (statement_uuid, isin)
);

CREATE INDEX ix_portfolio_holdings_entity_date ON portfolio_holdings (entity, statement_date);
