-- ČNB policy-rate and minimum-reserve facts consumed from fx-service's
-- fx.cnb-policy-rate.published.v1 (openbank.fx.cnb-policy-rate.published, compacted). The minimum
-- reserve requirement reads MIN_RESERVE_RATIO and MIN_RESERVE_REMUNERATION in effect on the as-of
-- date from here instead of the 2 % / 0 constants it used to carry; with no fact in effect the
-- requirement is NOT_EVALUABLE. `rate` is a fraction (0.04 = 4 %).
--
-- Keyed by (instrument, effective_from), the consumer's idempotency key: a redelivery is a no-op,
-- a different rate for a stored key is fx-service's revision and overwrites (revised = true).
--
-- Forward-only.
-- Rollback: DROP TABLE IF EXISTS cnb_policy_rate_fact; — the consumer group then
-- has to be reset to `earliest` to rebuild it from the compacted topic.
CREATE TABLE cnb_policy_rate_fact (
    instrument     VARCHAR(40)   NOT NULL,
    effective_from DATE          NOT NULL,
    rate           NUMERIC(12,8) NOT NULL CHECK (rate >= 0 AND rate <= 1),
    source_url     TEXT          NOT NULL,
    fetched_at     TIMESTAMPTZ   NOT NULL,
    content_sha256 VARCHAR(64)   NOT NULL,
    note           TEXT,
    revised        BOOLEAN       NOT NULL DEFAULT FALSE,
    received_at    TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (instrument, effective_from)
);
