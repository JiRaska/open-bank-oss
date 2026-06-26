-- SPDX-License-Identifier: MPL-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
--
-- ADR-0069/0078 / issue #869 (part c) — four-eyes (maker != checker) attestation for the
-- entity-level fiscal-year close.
--
-- Records WHO created/last-refreshed the DRAFT (the maker) so attestation can enforce that the
-- attestor (the checker) differs from the draft author. NULLABLE because existing rows predate
-- four-eyes tracking; the application layer treats a NULL author as a FAIL-CLOSED conflict at
-- attest time ("draft predates four-eyes tracking — refresh it"), never a silent bypass.
--
-- ADD COLUMN with no default + nullable ⇒ metadata-only, no table rewrite, online-safe.

ALTER TABLE ledger_year_close ADD COLUMN drafted_by VARCHAR(255) NULL;

-- Rollback:
--   ALTER TABLE ledger_year_close DROP COLUMN IF EXISTS drafted_by;
-- Safe to roll back: drafts are recomputable from the journal; dropping the column only removes
-- the maker attribution on un-attested drafts. Re-creating a draft re-populates it.
