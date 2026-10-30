-- SPDX-License-Identifier: Apache-2.0
-- ADR-0315 D4: a derivative consumes its counterparty's credit line by |notional| x a conversion
-- factor (by product and residual maturity), a reverse repo by the cash lent less the haircut
-- collateral. SYNTHETIC starting values, marked unverified: a person must confirm or replace them.
-- A derivative with no effective factor, or a reverse repo with no effective haircut, is refused at
-- submission, never measured as zero.
INSERT INTO reference_records (id, kind, record_key, version, status, valid_from, verified, payload,
                               created_by, created_by_type, created_at, reason)
SELECT nextval('reference_records_seq'), s.kind, s.record_key, 1, 'ACTIVE', DATE '2000-01-01', FALSE,
       s.payload, 'system:seed', 'SYSTEM', now(), 'seeded by V28 (ADR-0315 D4 credit measure)'
FROM (VALUES
    ('derivative-conversion-factor', 'IRS-1Y', '{"code":"IRS-1Y","name":"IRS credit conversion factor","product":"IRS","maxResidualYears":1,"factorPercent":0.5}'),
    ('derivative-conversion-factor', 'IRS-5Y', '{"code":"IRS-5Y","name":"IRS credit conversion factor","product":"IRS","maxResidualYears":5,"factorPercent":1.0}'),
    ('derivative-conversion-factor', 'IRS-OVER-5Y', '{"code":"IRS-OVER-5Y","name":"IRS credit conversion factor","product":"IRS","maxResidualYears":null,"factorPercent":1.5}'),
    ('derivative-conversion-factor', 'FRA-1Y', '{"code":"FRA-1Y","name":"FRA credit conversion factor","product":"FRA","maxResidualYears":1,"factorPercent":0.5}'),
    ('derivative-conversion-factor', 'FRA-5Y', '{"code":"FRA-5Y","name":"FRA credit conversion factor","product":"FRA","maxResidualYears":5,"factorPercent":1.0}'),
    ('derivative-conversion-factor', 'FRA-OVER-5Y', '{"code":"FRA-OVER-5Y","name":"FRA credit conversion factor","product":"FRA","maxResidualYears":null,"factorPercent":1.5}'),
    ('derivative-conversion-factor', 'FX_FORWARD-1Y', '{"code":"FX_FORWARD-1Y","name":"FX_FORWARD credit conversion factor","product":"FX_FORWARD","maxResidualYears":1,"factorPercent":2.0}'),
    ('derivative-conversion-factor', 'FX_FORWARD-5Y', '{"code":"FX_FORWARD-5Y","name":"FX_FORWARD credit conversion factor","product":"FX_FORWARD","maxResidualYears":5,"factorPercent":5.0}'),
    ('derivative-conversion-factor', 'FX_FORWARD-OVER-5Y', '{"code":"FX_FORWARD-OVER-5Y","name":"FX_FORWARD credit conversion factor","product":"FX_FORWARD","maxResidualYears":null,"factorPercent":7.5}'),
    ('derivative-conversion-factor', 'FX_SWAP-1Y', '{"code":"FX_SWAP-1Y","name":"FX_SWAP credit conversion factor","product":"FX_SWAP","maxResidualYears":1,"factorPercent":2.0}'),
    ('derivative-conversion-factor', 'FX_SWAP-5Y', '{"code":"FX_SWAP-5Y","name":"FX_SWAP credit conversion factor","product":"FX_SWAP","maxResidualYears":5,"factorPercent":5.0}'),
    ('derivative-conversion-factor', 'FX_SWAP-OVER-5Y', '{"code":"FX_SWAP-OVER-5Y","name":"FX_SWAP credit conversion factor","product":"FX_SWAP","maxResidualYears":null,"factorPercent":7.5}'),
    ('collateral-haircut', 'DEFAULT', '{"code":"DEFAULT","name":"Default collateral haircut","isin":"*","haircutPercent":10}')
) AS s(kind, record_key, payload);
--
-- Rollback: DELETE FROM reference_records WHERE created_by = 'system:seed'
--   AND kind IN ('derivative-conversion-factor', 'collateral-haircut') AND reason LIKE 'seeded by V28%';
