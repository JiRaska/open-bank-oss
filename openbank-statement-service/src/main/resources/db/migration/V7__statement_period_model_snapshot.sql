-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0035 §D/§F promise that re-rendering a closed period reproduces byte-identical output.
-- It did not: `StatementService.statementModel` rebuilt the canonical model at RENDER time from
-- two live projections — transaction-service booked entries for the (already closed) window, and
-- the account's current IBAN/holder name. A late entry booked into the closed window, or a holder
-- rename, silently changed an already-issued legal statement page (issue #3986).
--
-- This column freezes those inputs at close: JSON of the canonical `StatementSnapshot`
-- (iban, holderName, entries). It stores the canonical MODEL, not rendered bytes — ADR-0035 §F's
-- "no camt/MT/PDF is stored" is unchanged, and this is the option its own "Alternatives considered"
-- already chose ("persist the canonical model, render on demand").
--
-- Deliberately NULLABLE, and deliberately NOT backfilled. Periods closed before this migration have
-- no frozen inputs, and there is no honest way to invent them: the live projections may already
-- have drifted from what was issued, so a backfill would freeze the drift and stamp it as the
-- canonical document. Those rows keep replaying live data (logged as such) until they age out of
-- the 10y retention window or are restated (ADR-0035 §D), which mints a new page WITH a snapshot.
--
-- Rollback:
--   ALTER TABLE statement_period DROP COLUMN model_snapshot;
-- Safe and lossless with respect to every other column: nothing reads model_snapshot except the
-- render path, which falls back to the live projections when it is absent. Dropping it restores
-- the pre-#3986 behaviour for ALL periods rather than only the pre-V7 ones.

ALTER TABLE statement_period
    ADD COLUMN model_snapshot TEXT;

COMMENT ON COLUMN statement_period.model_snapshot IS
    'Frozen render inputs (StatementSnapshot JSON) captured at close so a re-render of this legal '
    'sequence is byte-identical (ADR-0035 D/F, issue #3986). NULL for periods closed before V7.';
