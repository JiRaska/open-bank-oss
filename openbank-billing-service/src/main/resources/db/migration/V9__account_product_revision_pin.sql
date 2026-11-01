-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0341 D1/D2/D3: billing prices an account from the catalog revision the account is PINNED to
-- and posts its fee with that revision's accounting class. Additive only.
-- Rollback: DROP TABLE billing_account_pin; ALTER TABLE assessed_fee DROP COLUMN product_revision_id,
-- DROP COLUMN accounting_class. Nothing reads them once the previous release is redeployed.

-- One row per account, written once: a pin is never silently replaced (a change of terms is an
-- explicit, auditable re-pin, not an UPDATE here). Pinned at the account's first assessment until
-- account-service records the pin at opening and billing reads it from there.
CREATE TABLE billing_account_pin (
    account_id           VARCHAR(64) PRIMARY KEY,
    product_revision_id  UUID        NOT NULL,
    pinned_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Nullable: fees assessed before the pin existed carry neither, and keep posting through the legacy
-- payload path (counted) until they drain.
ALTER TABLE assessed_fee ADD COLUMN product_revision_id UUID;
ALTER TABLE assessed_fee ADD COLUMN accounting_class VARCHAR(64);
