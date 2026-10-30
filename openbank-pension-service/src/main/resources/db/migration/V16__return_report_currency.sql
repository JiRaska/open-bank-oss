-- Multi-currency state-contribution return reports (ADR-0334, ZDPS §18(4)). A return report is
-- now single-currency: one report per currency and month, so its total never sums amounts of
-- different currencies. The report row records that currency and the total it filed.
--
-- Rollback: ALTER TABLE pension_return_reports DROP CONSTRAINT pension_return_reports_total_positive,
--   DROP COLUMN total, DROP COLUMN currency;
-- (the filed payload stays the evidence of what was sent; nothing else reads these columns).

ALTER TABLE pension_return_reports ADD COLUMN currency CHAR(3);
ALTER TABLE pension_return_reports ADD COLUMN total NUMERIC(19, 4);

-- Reports filed before this change carry no currency in their payload. Their currency is the one
-- of the returns they list (still linked unless refused and re-reported since); CZK otherwise,
-- the only currency the CZ packs issued contracts in. The total is read from the filed header
-- (`H;format;RETURNS;IČO;YYYY-MM;count;total`), i.e. exactly what was sent.
UPDATE pension_return_reports r
SET currency = COALESCE(
        (SELECT MIN(x.currency) FROM pension_state_contribution_returns x WHERE x.report_id = r.id),
        'CZK'
    ),
    total = split_part(split_part(r.payload, E'\n', 1), ';', 7)::NUMERIC(19, 4)
WHERE r.currency IS NULL;

ALTER TABLE pension_return_reports ALTER COLUMN currency SET NOT NULL;
ALTER TABLE pension_return_reports ALTER COLUMN total SET NOT NULL;
ALTER TABLE pension_return_reports
    ADD CONSTRAINT pension_return_reports_total_positive CHECK (total > 0);
