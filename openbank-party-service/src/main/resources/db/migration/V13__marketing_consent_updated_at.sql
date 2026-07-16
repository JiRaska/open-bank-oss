-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- Post-onboarding marketing-consent revocation (mobile app Profile screen). consent_marketing
-- (V12) already holds the current value and is updated in place; this column records WHEN it
-- last changed after onboarding, separate from consent_captured_at (V12), which stays the
-- immutable onboarding-time record and is never touched again.
--
-- consent_gdpr is deliberately NOT made revocable here: it isn't GDPR Art 6(1)(a) consent in
-- the first place (account operation runs on contract/legal-obligation basis), so there is no
-- "current value" to track for it.
--
-- Rollback note: DROP COLUMN is instant in Postgres (no-rewrite, logical catalog change).
ALTER TABLE parties
    ADD COLUMN consent_marketing_updated_at TIMESTAMPTZ;
