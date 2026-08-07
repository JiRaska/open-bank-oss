-- D5 merchant enrichment: a lookup table from a normalised acquirer descriptor to the merchant's
-- public trading identity and shop location.
--
-- Why a table and not a call: the acquirer descriptor is stable per merchant, so resolving it is a
-- dictionary lookup, not a per-request enquiry. Merchants move rarely; a stale row is a wrong
-- street, not a wrong merchant.
--
-- What this holds is PUBLIC BUSINESS data — where a shop is. It is deliberately not, and must
-- never become, where a CARDHOLDER was: nothing here is keyed by customer, card or transaction.
--
-- descriptor_key is produced by MerchantDescriptor.normalise() and matched exactly. Approximate
-- matching is not used anywhere: handing one merchant's coordinates to a similarly-named other is
-- a fabrication, and the read path renders nothing rather than guess.
CREATE TABLE merchant_catalog (
    descriptor_key VARCHAR(120) PRIMARY KEY,
    clean_name     VARCHAR(160) NOT NULL,
    logo_url       VARCHAR(400),
    category       VARCHAR(40),
    -- Geo is nullable on purpose. Card-not-present merchants (e-shops) have no meaningful shop
    -- location, and a made-up head-office pin on a map of "where you spent" would be a lie.
    lat            DOUBLE PRECISION,
    lon            DOUBLE PRECISION,
    city           VARCHAR(120),
    country        CHAR(2),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT merchant_catalog_geo_complete CHECK (
        (lat IS NULL AND lon IS NULL) OR (lat IS NOT NULL AND lon IS NOT NULL)
    ),
    CONSTRAINT merchant_catalog_lat_range CHECK (lat IS NULL OR (lat BETWEEN -90 AND 90)),
    CONSTRAINT merchant_catalog_lon_range CHECK (lon IS NULL OR (lon BETWEEN -180 AND 180))
);

COMMENT ON TABLE merchant_catalog IS
    'Acquirer descriptor -> public merchant identity + shop location. Public business data only; never cardholder location.';

-- Seed: well-known Czech merchants, public trading names and public shop coordinates.
-- E-shops carry no geo (card-not-present has no location to show).
INSERT INTO merchant_catalog (descriptor_key, clean_name, category, lat, lon, city, country) VALUES
    ('ALZACZ',        'Alza.cz',           'SHOPPING',   50.1027, 14.4640, 'Praha',   'CZ'),
    ('ROHLIKCZ',      'Rohlík.cz',         'GROCERIES',  NULL,    NULL,    NULL,      'CZ'),
    ('ROHLIKGROUP',   'Rohlík.cz',         'GROCERIES',  NULL,    NULL,    NULL,      'CZ'),
    ('BILLA',         'Billa',             'GROCERIES',  50.0834, 14.4238, 'Praha',   'CZ'),
    ('ALBERT',        'Albert',            'GROCERIES',  50.0875, 14.4213, 'Praha',   'CZ'),
    ('LIDL',          'Lidl',              'GROCERIES',  50.0796, 14.4300, 'Praha',   'CZ'),
    ('KAUFLAND',      'Kaufland',          'GROCERIES',  50.0723, 14.4405, 'Praha',   'CZ'),
    ('TESCOSTORES',   'Tesco',             'GROCERIES',  50.0815, 14.4270, 'Praha',   'CZ'),
    ('GLOBUS',        'Globus',            'GROCERIES',  50.0430, 14.5540, 'Praha',   'CZ'),
    ('DMDROGERIEMARKT', 'dm drogerie',     'HEALTH',     50.0870, 14.4210, 'Praha',   'CZ'),
    ('IKEA',          'IKEA',              'SHOPPING',   50.0430, 14.4900, 'Praha',   'CZ'),
    ('DATART',        'Datart',            'SHOPPING',   50.0860, 14.4400, 'Praha',   'CZ'),
    ('CSOB',          'ČSOB',              'FINANCE',    50.0448, 14.4030, 'Praha',   'CZ'),
    ('CESKAPOSTA',    'Česká pošta',       'SERVICES',   50.0870, 14.4280, 'Praha',   'CZ'),
    ('DPP',           'DPP',               'TRANSPORT',  50.0830, 14.4340, 'Praha',   'CZ'),
    ('CESKEDRAHY',    'České dráhy',       'TRANSPORT',  50.0830, 14.4356, 'Praha',   'CZ'),
    ('REGIOJET',      'RegioJet',          'TRANSPORT',  50.0862, 14.4400, 'Praha',   'CZ'),
    ('MCDONALDS',     'McDonald''s',       'DINING',     50.0810, 14.4270, 'Praha',   'CZ'),
    ('KFC',           'KFC',               'DINING',     50.0820, 14.4290, 'Praha',   'CZ'),
    ('STARBUCKS',     'Starbucks',         'DINING',     50.0875, 14.4210, 'Praha',   'CZ'),
    ('COSTACOFFEE',   'Costa Coffee',      'DINING',     50.0860, 14.4250, 'Praha',   'CZ'),
    ('BENZINA',       'Benzina',           'TRANSPORT',  50.0700, 14.4500, 'Praha',   'CZ'),
    ('SHELL',         'Shell',             'TRANSPORT',  50.0760, 14.4600, 'Praha',   'CZ'),
    ('OMV',           'OMV',               'TRANSPORT',  50.0740, 14.4700, 'Praha',   'CZ'),
    ('NETFLIXCOM',    'Netflix',           'ENTERTAINMENT', NULL, NULL,    NULL,      'NL'),
    ('SPOTIFY',       'Spotify',           'ENTERTAINMENT', NULL, NULL,    NULL,      'SE'),
    ('GOOGLE',        'Google',            'SERVICES',   NULL,    NULL,    NULL,      'IE'),
    ('APPLECOMBILL',  'Apple',             'SERVICES',   NULL,    NULL,    NULL,      'IE'),
    ('MALLCZ',        'MALL.CZ',           'SHOPPING',   NULL,    NULL,    NULL,      'CZ'),
    ('NOTINO',        'Notino',            'HEALTH',     NULL,    NULL,    NULL,      'CZ');
