-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0341: a contract's rate comes from the published catalog revision it was opened under, never
-- from "the currently published" one. An account that accrues from a catalog-sourced rate is pinned
-- to that revision on its first accrual; later revisions supersede the rate for NEW accounts only.
-- Changing an existing account's terms is an explicit re-pin (a new row under a new revision), not
-- a side effect of a catalog publish. catalog_interest_rate_snapshots stays the local cache, keyed
-- by revision id. Purely additive: pre-V19 binaries neither read nor write this table.

CREATE TABLE account_rate_revision_pins (
    id           UUID PRIMARY KEY,
    account_id   UUID        NOT NULL,
    product_id   VARCHAR(64) NOT NULL,
    currency     CHAR(3)     NOT NULL,
    revision_id  UUID        NOT NULL REFERENCES catalog_interest_rate_snapshots(revision_id),
    pinned_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_account_rate_revision_pin UNIQUE (account_id, product_id, currency)
);

CREATE INDEX idx_account_rate_revision_pins_revision ON account_rate_revision_pins(revision_id);

-- Rollback:
--   DROP TABLE account_rate_revision_pins;
-- Pre-V19 binaries resolve the rate by effective date again (a published successor then applies to
-- existing accounts from its effective_from). Retain the table in production rather than dropping
-- it: it is the only record of which revision each account accrued under.
