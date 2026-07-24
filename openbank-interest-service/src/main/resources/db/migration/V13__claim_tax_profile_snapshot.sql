-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- Issue #1355 — capitalize() retry could silently diverge row-vs-GL through the tax-profile axis.
--
-- capitalize()'s claim freezes the accrual SET (gross) before the ledger post, but it did NOT freeze
-- the withholding computation: taxProfilePort.resolve(accountId) was re-called fresh on every attempt,
-- including retries. The ledger idempotency key is amount-blind, so a retry after a crashed post
-- replays the ORIGINAL journal (P1's amounts) silently, while capitalizeSet recomputed tax/net from a
-- freshly-resolved profile (P2 if the account's tax attributes changed in between). The GL would then
-- reflect P1's split and the interest-service withholding row P2's — the same divergence class #1316
-- fixed for the gross axis, reopened through tax. Dormant today only because DefaultTaxProfileProvider
-- returns a constant; the account->party tax-attribute fast-follow is what would make it reachable.
--
-- Fix: snapshot the resolved TaxProfile at CLAIM time (the durable freeze point that survives a crash,
-- committed before the ledger post), alongside claimed_period_to. A retry replays the frozen profile
-- instead of re-resolving, so the recomputed tax/net matches the replayed journal exactly. These
-- columns are NULL until an accrual is claimed (and NULL for any claim already in flight at deploy —
-- capitalizeSet falls back to a fresh resolve for those, which is safe while resolution is constant).

ALTER TABLE interest_accruals
    ADD COLUMN claimed_taxpayer_type          VARCHAR(16),
    ADD COLUMN claimed_residency              VARCHAR(16),
    ADD COLUMN claimed_treaty_rate            NUMERIC(6, 4),
    ADD COLUMN claimed_non_cooperating_state  BOOLEAN,
    ADD COLUMN claimed_exempt_code            VARCHAR(64);

-- Rollback:
--   ALTER TABLE interest_accruals
--       DROP COLUMN claimed_taxpayer_type,
--       DROP COLUMN claimed_residency,
--       DROP COLUMN claimed_treaty_rate,
--       DROP COLUMN claimed_non_cooperating_state,
--       DROP COLUMN claimed_exempt_code;
-- Safe: the columns are advisory snapshots read only on a capitalize() retry; dropping them reverts
-- to re-resolving the profile on retry (the pre-#1355 behaviour).
