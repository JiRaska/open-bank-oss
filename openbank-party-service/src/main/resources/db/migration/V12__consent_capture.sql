-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- Onboarding consent capture (mobile app "Agreement" step, ADR-0069). The app has collected
-- a GDPR (required) and marketing (optional) checkbox since the onboarding UI shipped, but the
-- edge never forwarded them and party-service had nowhere to store them — both flags were
-- silently dropped. This does not retro-fix past onboardings: existing rows get NULL
-- (not asked/answered), which is the correct honest value, not FALSE.
--
-- consent_captured_at is stamped only when at least one flag is present, giving a minimal
-- "when was consent given" audit trail. This does NOT version the consent text itself — if the
-- wording changes materially, that needs a follow-up (e.g. a consent_version column).
--
-- Rollback note: DROP COLUMN is instant in Postgres (no-rewrite, logical catalog change).
ALTER TABLE parties
    ADD COLUMN consent_gdpr        BOOLEAN,
    ADD COLUMN consent_marketing   BOOLEAN,
    ADD COLUMN consent_captured_at TIMESTAMPTZ;
