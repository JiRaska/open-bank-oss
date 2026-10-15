-- ČNB monetary-policy rates and minimum-reserve facts (2W repo, discount, lombard, PMR ratio and
-- PMR remuneration), each a step function of its effective date. fx-service DOWNLOADS all of them
-- (the three rate histories and the PMR workbook) daily and on boot, and publishes every new or
-- revised row as fx.cnb-policy-rate.published.v1; the risk engine reads the minimum-reserve
-- ratio and remuneration from that stream instead of a constant.
--
-- No operative value is seeded: ingestion is the only source of truth, and a date before the
-- source's earliest representable row has no fact (NOT_EVALUABLE downstream).
--
-- `rate` is a FRACTION (3,75 % in the feed is stored as 0.0375), the same unit the risk engine
-- multiplies by. `published_at` is NULL until the row's event is in fx_outbox: ingestion and the
-- outbox row commit in one transaction, so a NULL here means "no event yet", never "event lost".
--
-- Forward-only.
-- Rollback: Only before any row has been published downstream, drop the table:
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
