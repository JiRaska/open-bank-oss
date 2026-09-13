-- Rollback: DROP TABLE merchant_logo; ALTER TABLE merchant_catalog DROP COLUMN logo_etag;
-- Both are additive, so the previous release runs unchanged against this schema and the rollback
-- loses only the ingested bitmaps — every one of which can be re-uploaded, since the catalogue row
-- and its provenance (merchant_catalog.logo_url) survive.
--
-- Self-hosted merchant logos for transaction enrichment.
--
-- WHY THE BYTES LIVE HERE AND NOT AS A LINK TO SOMEBODY ELSE'S CDN.
-- `merchant_catalog.logo_url` has been a nullable column since V16 and was never populated. The
-- obvious way to populate it — point it at a logo CDN and let the customer's app load the image —
-- is the one thing this must not do. Every such request tells that CDN the customer's IP address
-- and which merchant they transacted with, at the moment they open their statement: the third
-- party reconstructs a spending profile from nothing but its access log, and no consent covers it.
-- So the bytes are ingested once, server-side, and served from this bank's own origin.
--
-- `logo_url` therefore changes meaning rather than getting populated: it records WHERE a logo was
-- obtained (provenance and licence evidence), and is operator-facing only. The customer-facing
-- `merchant.logoUrl` is derived from `logo_etag` below and always points at this service.
CREATE TABLE merchant_logo (
    descriptor_key VARCHAR(120) PRIMARY KEY REFERENCES merchant_catalog (descriptor_key) ON DELETE CASCADE,
    -- Two pre-rendered square variants. A statement row renders at 64 CSS px (128 on a 2x screen),
    -- and both are a few kB, so storing them beats resizing per request or shipping one large
    -- image the client scales down.
    bytes_64       BYTEA NOT NULL,
    bytes_128      BYTEA NOT NULL,
    -- Always image/png today. Carried explicitly rather than assumed, so adding a second output
    -- format later is a value change and not a migration of every reader.
    content_type   VARCHAR(40) NOT NULL,
    -- SHA-256 (hex) of bytes_128. Serves as the HTTP ETag and as the cache-busting token in the
    -- derived logoUrl, so a corrected logo reaches clients without any TTL having to expire.
    content_hash   CHAR(64) NOT NULL,
    -- Provenance. `source_url` is where the bytes came from, `licence` how they may be used, and
    -- `attribution` the credit line a licence may require (ODbL and CC-BY sources do). A logo with
    -- no recorded licence is a trademark this bank is republishing on nothing but hope.
    source_url     VARCHAR(400),
    licence        VARCHAR(60),
    attribution    VARCHAR(200),
    uploaded_by    VARCHAR(120),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT merchant_logo_bytes_64_size CHECK (octet_length(bytes_64) BETWEEN 1 AND 65536),
    CONSTRAINT merchant_logo_bytes_128_size CHECK (octet_length(bytes_128) BETWEEN 1 AND 262144)
);

COMMENT ON TABLE merchant_logo IS
    'Self-hosted merchant logo bitmaps. Served from this origin so no third party learns who a customer paid.';

-- Presence marker on the catalogue row, so the enrichment read path — one query per statement
-- page — never needs a join or a second round trip to decide whether a logo exists. NULL means no
-- logo; the value is merchant_logo.content_hash and is maintained by the same write.
ALTER TABLE merchant_catalog ADD COLUMN logo_etag CHAR(64);

COMMENT ON COLUMN merchant_catalog.logo_url IS
    'Provenance only: where the logo was obtained. Operator-facing, never sent to a customer client.';
COMMENT ON COLUMN merchant_catalog.logo_etag IS
    'merchant_logo.content_hash when a logo exists, else NULL. Denormalised for the per-page enrichment read.';
