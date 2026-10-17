-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- ADR-0341: V18 pins an account at its FIRST accrual. Accounts that already accrued before catalog
-- sync was switched on have no pin, and without this backfill the first post-sync accrual would pin
-- them to whatever revision is current - a silent repricing of a live contract. Pin every existing
-- account to the revision whose cached config its LATEST accrual used. An account whose latest
-- accrual used an operator-managed config (no catalog snapshot) gets no pin and keeps operator
-- semantics. Reversed accruals are not history. Idempotent: an existing pin is never replaced.

-- Fail loudly rather than guess: one cached config must map to exactly one APPLIED revision.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM catalog_interest_rate_snapshots
        WHERE outcome = 'APPLIED' AND config_id IS NOT NULL
        GROUP BY config_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V19 backfill: a rate config maps to more than one APPLIED catalog revision; resolve manually';
    END IF;
END $$;

INSERT INTO account_rate_revision_pins (id, account_id, product_id, currency, revision_id, pinned_at)
SELECT gen_random_uuid(), l.account_id, l.product_id, l.currency, s.revision_id, NOW()
FROM (
    SELECT DISTINCT ON (account_id, product_id, currency)
           account_id, product_id, currency, config_id
    FROM interest_accruals
    WHERE status <> 'REVERSED'
    ORDER BY account_id, product_id, currency, accrual_date DESC
) l
JOIN catalog_interest_rate_snapshots s
  ON s.config_id = l.config_id AND s.outcome = 'APPLIED'
ON CONFLICT (account_id, product_id, currency) DO NOTHING;

-- Rollback (data only, no schema change):
--   DELETE FROM account_rate_revision_pins WHERE pinned_at <= <timestamp of this migration>;
-- Run it only before any post-V19 first accrual has been pinned; afterwards the pins are the only
-- record of which revision each account accrued under, so retain them in production.
