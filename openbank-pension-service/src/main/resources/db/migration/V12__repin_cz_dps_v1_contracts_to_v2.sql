-- ADR-0334 final integration: re-pin CZ/DPS pack v1 contracts to v2.
--
-- Pack v1 and v2 model the SAME statute (ZDPS 427/2011). v2 differs only by the whole-crown
-- rounding of the state contribution (§14(4), always the law) and by filing through the real MF
-- quarterly channel (cz-mf-state-contribution-v1, §16). v1's claim format agency-monthly-batch-v0
-- was a placeholder with no adapter: a contract still pinned to v1 would keep its state-contribution
-- claims PENDING forever, i.e. the participant would silently never receive a statutory benefit.
-- Pinning (ADR-0212 D3) protects a participant from a CHANGE OF LAW, not from our modelling error,
-- so the correct remedy is an explicit, audited re-pin rather than keeping a fake v1 filing format.
-- Claims already filed keep the claim_format they were filed under; only future claims move.
--
-- Rollback: not automatic. Re-pinning back to v1 would strand claims again; restore from the
--   pre-migration backup if ever required (UPDATE ... SET pack_version = 1 WHERE contract_id IN (...)).

UPDATE pension_contracts
SET pack_version = 2
WHERE jurisdiction = 'CZ'
  AND product_line = 'DPS'
  AND pack_version = 1;
