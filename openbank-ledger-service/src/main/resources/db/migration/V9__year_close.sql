-- SPDX-License-Identifier: MPL-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
--
-- ADR-0078 D5 / issue #471, increment 1 — entity-level fiscal-year close record.
--
-- One row per fiscal year: the frozen totals + SHA-256 content hash (the attestation anchor)
-- of the canonical trial-balance JSON the close was computed from, and the attestation trail.
-- Lifecycle DRAFT -> ATTESTED is enforced in the application layer (fail-closed hash re-check
-- at attest time). The trial-balance computation itself is a read-only aggregation over the
-- existing journal_lines/journal_entries/gl_accounts tables — no schema change needed there.
--
-- NEW table only; purely additive and online-safe (no lock on hot journal tables).

CREATE TABLE ledger_year_close (
    id            UUID PRIMARY KEY,
    fiscal_year   INT          NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT chk_year_close_status CHECK (status IN ('DRAFT', 'ATTESTED')),
    computed_at   TIMESTAMPTZ  NOT NULL,
    total_debits  NUMERIC(20,6) NOT NULL,
    total_credits NUMERIC(20,6) NOT NULL,
    account_count INT          NOT NULL,
    content_hash  VARCHAR(64)  NOT NULL,
    attested_by   VARCHAR(255),
    attested_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_year_close_fiscal_year UNIQUE (fiscal_year)
);

-- Rollback:
--   DROP TABLE IF EXISTS ledger_year_close;
-- Safe to roll back BEFORE any year is ATTESTED: drafts are recomputable from the journal at
-- any time. After an attestation the row is signed audit evidence (zákon 563/1991 průkaznost);
-- dropping it then loses the attestation trail (the underlying journal data is unaffected) —
-- archive the rows first if a rollback is ever needed past that point.
