-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- GDPR Art. 17 / ADR-0118 (#15755): pid-service honours the party-service PARTY_ERASED event.
-- `erased_at` marks a row whose personal data has been irreversibly anonymised in place. It is
-- the idempotency marker (a re-delivered event finds the stamp and does nothing) and the retention
-- clock, exactly as `kyc_cases.erased_at` is for kyc-service (V6 there): the anonymised shell is
-- kept, the personal columns are not.
--
-- Additive and nullable: no row is modified, no existing reader is affected.
--
-- Rollback note: the column holds no personal data and nothing but the erasure path reads it, so
-- a rollback is safe at any time (the anonymisation itself is irreversible by design):
--   DROP INDEX IF EXISTS idx_parties_erased_at;
--   ALTER TABLE identity_verification_case DROP COLUMN IF EXISTS erased_at;
--   ALTER TABLE parties DROP COLUMN IF EXISTS erased_at;

ALTER TABLE parties ADD COLUMN IF NOT EXISTS erased_at TIMESTAMPTZ;
ALTER TABLE identity_verification_case ADD COLUMN IF NOT EXISTS erased_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_parties_erased_at ON parties (erased_at) WHERE erased_at IS NOT NULL;
