-- ADR-0313 D4 / ADR-0314 D2: operator-uploaded curve sets. The input quotes AND the bootstrapped
-- pillars are stored; reads rebuild curves from the pillars, so a set means the same numbers after
-- the bootstrap method changes. Cash flows are NOT stored (ADR-0314 D6) — they are derived per run.
-- Rollback:
--   DROP TABLE curve_set_pillar; DROP TABLE curve_set_quote; DROP TABLE curve_set;

CREATE TABLE curve_set (
    id           UUID PRIMARY KEY,
    as_of        DATE NOT NULL,
    provenance   VARCHAR(16) NOT NULL CHECK (provenance IN ('synthetic', 'production')),
    source       VARCHAR(256) NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_curve_set_as_of ON curve_set (as_of, recorded_at DESC);

CREATE TABLE curve_set_quote (
    id            BIGSERIAL PRIMARY KEY,
    curve_set_id  UUID NOT NULL REFERENCES curve_set (id),
    curve_index   VARCHAR(16) NOT NULL
        CHECK (curve_index IN ('CZEONIA', 'PRIBOR_1M', 'PRIBOR_3M', 'PRIBOR_6M', 'ESTR', 'EURIBOR_3M')),
    tenor         VARCHAR(8) NOT NULL,
    -- Unconstrained NUMERIC: a stored rate must be exactly the rate that was bootstrapped.
    simple_rate   NUMERIC NOT NULL,
    CONSTRAINT uq_curve_set_quote UNIQUE (curve_set_id, curve_index, tenor)
);

CREATE TABLE curve_set_pillar (
    id            BIGSERIAL PRIMARY KEY,
    curve_set_id  UUID NOT NULL REFERENCES curve_set (id),
    curve_index   VARCHAR(16) NOT NULL
        CHECK (curve_index IN ('CZEONIA', 'PRIBOR_1M', 'PRIBOR_3M', 'PRIBOR_6M', 'ESTR', 'EURIBOR_3M')),
    pillar_date   DATE NOT NULL,
    zero_rate     NUMERIC NOT NULL,
    CONSTRAINT uq_curve_set_pillar UNIQUE (curve_set_id, curve_index, pillar_date)
);

CREATE INDEX idx_curve_set_pillar_set ON curve_set_pillar (curve_set_id);
