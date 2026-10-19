-- ADR-0344 D8: curve sets bootstrap from deposits, FRAs and par swaps, and the bank's own deals
-- carry legs and treasury's stored cash flows into the valuation reads.
--
-- 1. curve_set_quote gains the instrument a quote prices. Existing rows are deposits (the only
--    instrument before this migration), so the default states a fact, not a guess. The tenor code
--    of a FRA is `<m>x<n>` and of a swap `<n>Y`; uniqueness now includes the instrument.
-- 2. treasury_deal gains the ADR-0344 D2 fields: kind (ACTUAL | PLANNED | HYPOTHETICAL; a deal
--    seen before this migration is ACTUAL, the only kind treasury booked), book id, the legs as
--    published (LegSpec JSON) and the schedule hash. All nullable or defaulted: old events parse.
-- 3. treasury_deal_cash_flows keeps the LATEST `treasury.cashflow.generated` version per deal
--    (ADR-0344 D4: the engine consumes treasury's stored flows for own deals).
-- 4. snapshot_run records the curve and valuation model versions (nullable: older runs have none).
--
-- Rollback:
--   DROP TABLE treasury_deal_cash_flows;
--   ALTER TABLE treasury_deal DROP COLUMN kind, DROP COLUMN book_id, DROP COLUMN legs,
--       DROP COLUMN schedule_hash;
--   DELETE FROM curve_set_quote WHERE instrument <> 'DEPOSIT';
--   ALTER TABLE curve_set_quote DROP CONSTRAINT uq_curve_set_quote;
--   ALTER TABLE curve_set_quote ADD CONSTRAINT uq_curve_set_quote UNIQUE (curve_set_id, curve_index, tenor);
--   ALTER TABLE curve_set_quote DROP COLUMN instrument;
--   ALTER TABLE snapshot_run DROP COLUMN curve_model_version, DROP COLUMN valuation_model_version;

ALTER TABLE curve_set_quote
    ADD COLUMN instrument VARCHAR(8) NOT NULL DEFAULT 'DEPOSIT'
        CHECK (instrument IN ('DEPOSIT', 'FRA', 'SWAP'));
ALTER TABLE curve_set_quote DROP CONSTRAINT uq_curve_set_quote;
ALTER TABLE curve_set_quote ADD CONSTRAINT uq_curve_set_quote UNIQUE (curve_set_id, curve_index, instrument, tenor);

ALTER TABLE treasury_deal
    ADD COLUMN kind          VARCHAR(16) NOT NULL DEFAULT 'ACTUAL'
        CHECK (kind IN ('ACTUAL', 'PLANNED', 'HYPOTHETICAL')),
    ADD COLUMN book_id       VARCHAR(64),
    ADD COLUMN legs          JSONB,
    ADD COLUMN schedule_hash VARCHAR(128);

CREATE TABLE treasury_deal_cash_flows (
    deal_id       UUID         PRIMARY KEY,
    deal_version  INTEGER      NOT NULL,
    schedule_hash VARCHAR(128) NOT NULL,
    flows         JSONB        NOT NULL,
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE snapshot_run
    ADD COLUMN curve_model_version     TEXT,
    ADD COLUMN valuation_model_version TEXT;
