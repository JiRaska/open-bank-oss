-- ČNB monetary-policy rates and minimum-reserve facts (2W repo, discount, lombard, PMR ratio and
-- PMR remuneration), each a step function of its effective date. fx-service ingests the three rate
-- feeds daily and publishes every new or revised row as fx.cnb-policy-rate.published.v1; the risk
-- engine reads the minimum-reserve ratio and remuneration from that stream instead of a constant.
--
-- `rate` is a FRACTION (3,75 % in the feed is stored as 0.0375), the same unit the risk engine
-- multiplies by. `published_at` is NULL until the row's event is in fx_outbox: ingestion and the
-- outbox row commit in one transaction, so a NULL here means "no event yet", never "event lost".
--
-- Forward-only. Rollback (only before any row has been published downstream):
--   DROP TABLE IF EXISTS cnb_policy_rate;
-- After publication the risk engine holds copies; dropping this table orphans them silently.
CREATE TABLE cnb_policy_rate (
    id             UUID          PRIMARY KEY,
    instrument     VARCHAR(40)   NOT NULL,
    effective_from DATE          NOT NULL,
    rate           NUMERIC(12,8) NOT NULL,
    source_url     TEXT          NOT NULL,
    fetched_at     TIMESTAMPTZ   NOT NULL,
    content_sha256 VARCHAR(64)   NOT NULL,
    note           TEXT,
    previous_rate  NUMERIC(12,8),
    revised_at     TIMESTAMPTZ,
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT cnb_policy_rate_instrument_effective_from_key UNIQUE (instrument, effective_from),
    CONSTRAINT cnb_policy_rate_rate_is_fraction CHECK (rate >= 0 AND rate <= 1),
    CONSTRAINT cnb_policy_rate_sha256_hex CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);

-- The publish step scans for unpublished rows on every run.
CREATE INDEX idx_cnb_policy_rate_unpublished ON cnb_policy_rate (instrument, effective_from)
    WHERE published_at IS NULL;

-- Minimum-reserve FACTS. The ČNB publishes the PMR history only as a spreadsheet with one sheet
-- per year (no machine feed), so until that file is ingested automatically these two rows are the
-- cited facts, inserted here with their provenance. content_sha256 is the SHA-256 of
-- PMR_historie_zmen.xlsx as downloaded on 2026-10-04. Only rows with a citable effective date are
-- seeded: the 2 % ratio that preceded 2025-01-02 is not, so a snapshot before that date gets no
-- ratio and its requirement is NOT stated rather than computed at a guessed rate.
INSERT INTO cnb_policy_rate (id, instrument, effective_from, rate, source_url, fetched_at, content_sha256, note)
VALUES
    ('7b0f3c0e-5b1e-4c55-9d0a-1f6c2a8e4b01', 'MIN_RESERVE_RATIO', DATE '2025-01-02', 0.04,
     'https://www.cnb.cz/export/sites/cnb/cs/financni-trhy/.galleries/penezni_trh/download/PMR_historie_zmen.xlsx',
     TIMESTAMPTZ '2026-10-04 00:00:00+00',
     '43ffa8b5bedfd02d28970eacd7ea41cee83434373fb07856c6483c6383526e38',
     '4 % of the reserve base from 2025-01-02: Vyhláška č. 323/2024 Sb.; ČNB press release of 2024-10-10'),
    ('7b0f3c0e-5b1e-4c55-9d0a-1f6c2a8e4b02', 'MIN_RESERVE_REMUNERATION', DATE '2023-10-05', 0,
     'https://www.cnb.cz/export/sites/cnb/cs/financni-trhy/.galleries/penezni_trh/download/PMR_historie_zmen.xlsx',
     TIMESTAMPTZ '2026-10-04 00:00:00+00',
     '43ffa8b5bedfd02d28970eacd7ea41cee83434373fb07856c6483c6383526e38',
     'Required reserves unremunerated (0 %) from 2023-10-05 (PMR_historie_zmen.xlsx)');
