-- Per-account interest rate override + CURRENT defaults to ZERO interest.
--
-- Product decision: the everyday CURRENT account earns NO interest by default — the bank keeps the
-- float on the settled balance ("sedlina"). That's the reversal of V10 (which had seeded CURRENT at
-- 2.5%): here the CURRENT product config is deactivated, so a plain current account accrues nothing.
--
-- But interest must be *configurable per account*: a specific customer can be granted a rate on
-- their current account. So interest_rate_configs gains a nullable account_id — a row with
-- account_id set is an account-specific override that wins over the product-level default; NULL is
-- the product-wide default (SAVINGS 4%, and now no active CURRENT default). The accrual lookup
-- prefers the account-specific row (findEffectiveRate).

ALTER TABLE interest_rate_configs ADD COLUMN account_id UUID NULL;

-- One active override per (account) and one active default per (product) — a partial unique index
-- for each, so a second overlapping active config can't silently shadow the first.
CREATE UNIQUE INDEX ux_rate_active_account ON interest_rate_configs (account_id)
    WHERE active AND account_id IS NOT NULL;

-- CURRENT (…00c2) defaults to zero interest: deactivate V10's product-level config. Savings (…00c3)
-- stays active at 4%. A current account only accrues if it is given an explicit account_id override.
UPDATE interest_rate_configs
SET active = FALSE, updated_at = NOW()
WHERE product_id = '00000000-0000-0000-0000-0000000000c2' AND account_id IS NULL;
