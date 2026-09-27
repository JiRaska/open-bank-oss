-- SPDX-License-Identifier: Apache-2.0
-- #10896 (ADR-0315 treasury products gap): product CNB_LOMBARD — overnight borrowing from the ČNB
-- marginal lending (lombard) facility. Same columns and lifecycle as MM_BORROWING; the domain holds
-- the product rules (CZK, counterparty CNB, next-business-day maturity, rate > 0). The collateral
-- pledge is not modelled. V7 by queue assignment: #11041 (FX spot) takes V5, #11052 (nostro
-- statements) V6; intended merge order #11036 -> #11041 -> #11052 -> this.
--
-- #11041's V5 rewrites deals_product_check to add FX_SPOT, so this list ALSO carries FX_SPOT: V7
-- runs after V5 and must not drop it. Before V5 exists the extra value is merely permitted, never
-- produced (the FX_SPOT enum value lands with V5's code).
--
-- Rollback (only while no CNB_LOMBARD row exists — check first; a settled lombard deal has posted
-- to the ledger and must be reversed there, never deleted):
--   ALTER TABLE deals DROP CONSTRAINT deals_product_check;
--   ALTER TABLE deals ADD CONSTRAINT deals_product_check
--       CHECK (product IN ('MM_PLACEMENT', 'MM_BORROWING', 'CNB_DEPOSIT_FACILITY', 'FX_SPOT'));

ALTER TABLE deals DROP CONSTRAINT IF EXISTS deals_product_check;
ALTER TABLE deals ADD CONSTRAINT deals_product_check
    CHECK (product IN ('MM_PLACEMENT', 'MM_BORROWING', 'CNB_DEPOSIT_FACILITY', 'FX_SPOT', 'CNB_LOMBARD'));
