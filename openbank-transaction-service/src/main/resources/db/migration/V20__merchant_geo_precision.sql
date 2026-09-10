-- Rollback: ALTER TABLE merchant_catalog DROP COLUMN geo_precision; DROP TABLE merchant_location;
-- Additive in both directions: dropping the column loses only the honesty marker and the previous
-- release, which never reads it, runs unchanged. The seeded coordinates themselves are untouched by
-- this migration — see below for why they are demoted rather than deleted.
--
-- WHAT WAS WRONG WITH THE GEO WE ALREADY HAD.
-- V16 seeded one coordinate per BRAND: 'BILLA' is pinned at 50.0834, 14.4238 — a Prague address.
-- Every Billa transaction in the country therefore resolved to that pin, so a customer's shop in
-- Brno rendered 185 km away on a map captioned "where you spent". Nothing was broken; the data
-- said precisely what it was asked and it was a fiction, because a chain has no single location.
--
-- The fix is not better coordinates. It is admitting which question a row can answer:
--
--   EXACT — this is where the money was spent. A single-site merchant, or a location resolved from
--           the physical device that took the payment.
--   CITY  — the merchant trades in this town and this pin is representative, not the shop. A client
--           may caption a town; it must not drop a pin claiming a street.
--
-- Existing seeded rows are demoted to CITY rather than having their coordinates deleted, because
-- the town they name IS true and useful — it is only the precision that was overstated.
ALTER TABLE merchant_catalog
    ADD COLUMN geo_precision VARCHAR(8) NOT NULL DEFAULT 'CITY'
        CHECK (geo_precision IN ('EXACT', 'CITY'));

COMMENT ON COLUMN merchant_catalog.geo_precision IS
    'What the coordinates on this row can answer: EXACT = where the money was spent, CITY = the merchant trades in this town and the pin is representative.';

-- Per-site locations, one row per place the merchant actually trades.
--
-- `city_token` is the town as it appears in the ACQUIRER DESCRIPTOR, upper-cased and accent-folded
-- — the very token MerchantDescriptor.normalise() strips to reach the lookup key. Stripping it is
-- right for identifying the merchant and wasteful for locating them: "BILLA PRAHA 4" and
-- "BILLA BRNO" are the same merchant in different places, and the difference was being discarded.
-- This table is where it lands.
--
-- `terminal_id` is nullable and, today, always null: no acquirer feed in this fleet carries a
-- terminal or ATM identifier yet. It is declared because a device id is the only thing that can
-- ever justify EXACT for a chain, and a row that later acquires one needs no schema change — NOT
-- because anything populates it. Adding a column nobody writes is how merchant_catalog itself sat
-- empty for months (#8573); this one is deliberately empty and says so.
CREATE TABLE merchant_location (
    descriptor_key VARCHAR(120) NOT NULL REFERENCES merchant_catalog (descriptor_key) ON DELETE CASCADE,
    city_token     VARCHAR(60)  NOT NULL,
    lat            DOUBLE PRECISION NOT NULL,
    lon            DOUBLE PRECISION NOT NULL,
    city           VARCHAR(120),
    country        CHAR(2),
    geo_precision  VARCHAR(8) NOT NULL DEFAULT 'CITY' CHECK (geo_precision IN ('EXACT', 'CITY')),
    terminal_id    VARCHAR(64),
    source         VARCHAR(120),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (descriptor_key, city_token),
    CONSTRAINT merchant_location_lat_range CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT merchant_location_lon_range CHECK (lon BETWEEN -180 AND 180),
    -- EXACT is earned, not asserted: only a row tied to the device that took the payment may claim
    -- it. Without this a well-meaning operator can retype the chain-pin mistake one row lower down.
    CONSTRAINT merchant_location_exact_needs_terminal CHECK (geo_precision <> 'EXACT' OR terminal_id IS NOT NULL)
);

COMMENT ON TABLE merchant_location IS
    'Per-town (and, once an acquirer feed supplies terminal ids, per-device) merchant locations, keyed by the town token the acquirer descriptor carries.';

-- The seeded brands are chains; their coordinates were never a shop. The DEFAULT above already
-- writes CITY for existing rows, so this statement exists only to make the demotion explicit and
-- to survive a future change of that default.
UPDATE merchant_catalog SET geo_precision = 'CITY' WHERE lat IS NOT NULL;
