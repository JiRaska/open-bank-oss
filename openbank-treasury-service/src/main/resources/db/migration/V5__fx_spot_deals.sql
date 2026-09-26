-- SPDX-License-Identifier: Apache-2.0
-- #10896 (ADR-0315 treasury products gap): FX spot deals. The bank buys or sells a foreign currency
-- against CZK. `currency`/`principal` are the foreign leg, `rate` the dealer's rate in CZK per unit,
-- fx_counter_amount the CZK leg (principal x rate, 2 dp). A spot has no maturity: maturity_date
-- equals value_date and the deal is final once SETTLED. fx_mid_rate / fx_rate_flag record the
-- tolerance check against fx-service's mid (flag, never block). The four FX columns are NULL for
-- every money-market deal, so existing rows satisfy every new constraint unchanged.
--
-- Rollback (only while no FX_SPOT row exists — check first):
--   (an FX deal that has posted to the ledger must be reversed there first; never delete it)
--   ALTER TABLE deals DROP CONSTRAINT deals_fx_terms, DROP CONSTRAINT deals_fx_side_check;
--   ALTER TABLE deals DROP COLUMN fx_side, DROP COLUMN fx_counter_amount, DROP COLUMN fx_mid_rate,
--       DROP COLUMN fx_rate_flag;
--   then restore the V1 forms of deals_product_check and deals_dates_ordered.

ALTER TABLE deals DROP CONSTRAINT IF EXISTS deals_product_check;
ALTER TABLE deals ADD CONSTRAINT deals_product_check
    CHECK (product IN ('MM_PLACEMENT', 'MM_BORROWING', 'CNB_DEPOSIT_FACILITY', 'FX_SPOT'));

ALTER TABLE deals DROP CONSTRAINT IF EXISTS deals_dates_ordered;
ALTER TABLE deals ADD CONSTRAINT deals_dates_ordered CHECK (
    value_date >= trade_date
    AND (maturity_date > value_date OR (product = 'FX_SPOT' AND maturity_date = value_date))
);

ALTER TABLE deals
    ADD COLUMN fx_side            VARCHAR(4),
    ADD COLUMN fx_counter_amount  NUMERIC(20, 2),
    ADD COLUMN fx_mid_rate        NUMERIC(20, 10),
    ADD COLUMN fx_rate_flag       TEXT;

ALTER TABLE deals ADD CONSTRAINT deals_fx_side_check CHECK (fx_side IS NULL OR fx_side IN ('BUY', 'SELL'));

-- FX terms exist exactly for FX_SPOT, and an FX spot is never CZK against CZK.
ALTER TABLE deals ADD CONSTRAINT deals_fx_terms CHECK (
    (product = 'FX_SPOT' AND fx_side IS NOT NULL AND fx_counter_amount > 0 AND currency <> 'CZK')
    OR (product <> 'FX_SPOT' AND fx_side IS NULL AND fx_counter_amount IS NULL
        AND fx_mid_rate IS NULL AND fx_rate_flag IS NULL)
);
