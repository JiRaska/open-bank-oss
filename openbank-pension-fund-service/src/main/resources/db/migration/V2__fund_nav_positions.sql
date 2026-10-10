-- #12425: the positions a NAV was struck on, kept with it, so the portfolio composition at any
-- reporting date can be re-read (ČNB PSP 34-12). Written once by NAV calculation, never updated.
-- positions_recorded separates "struck on no positions" from "struck before this table existed":
-- the second is UNKNOWN to a report, never an empty portfolio.
-- Rollback:
--   DROP TABLE fund_nav_positions; ALTER TABLE fund_navs DROP COLUMN positions_recorded;

ALTER TABLE fund_navs ADD COLUMN positions_recorded BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE fund_nav_positions (
    id             UUID PRIMARY KEY,
    nav_id         UUID NOT NULL REFERENCES fund_navs (id),
    instrument_id  TEXT NOT NULL,
    quantity       NUMERIC NOT NULL CHECK (quantity >= 0),
    price          NUMERIC NOT NULL CHECK (price >= 0)
);

CREATE INDEX idx_fund_nav_positions_nav ON fund_nav_positions (nav_id);
CREATE INDEX idx_unit_transactions_fund_nav ON unit_transactions (fund_id, nav_id);
