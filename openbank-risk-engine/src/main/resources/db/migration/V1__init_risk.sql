-- ADR-0314 D1–D3: balance-sheet snapshot runs, their tie-out mismatches and positions, plus the
-- FX fixing reference data consumed from fx-service (D5).
-- Rollback:
--   DROP TABLE fx_fixing_rate; DROP TABLE snapshot_position;
--   DROP TABLE snapshot_tie_out_mismatch; DROP TABLE snapshot_run;

-- One run manifest. Natural key (as_of, input_hash): the same ledger at the same as-of is the
-- same knowledge and the same run; a different hash is newer knowledge and a new run, ordered by
-- recorded_at (the second bitemporal axis).
CREATE TABLE snapshot_run (
    id              UUID PRIMARY KEY,
    as_of           DATE NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL,
    input_hash      CHAR(64) NOT NULL,
    provenance      VARCHAR(16) NOT NULL CHECK (provenance IN ('synthetic', 'production')),
    status          VARCHAR(16) NOT NULL CHECK (status IN ('TIED_OUT', 'UNTIED')),
    position_count  INTEGER NOT NULL,
    mismatch_count  INTEGER NOT NULL,
    CONSTRAINT uq_snapshot_run_natural_key UNIQUE (as_of, input_hash),
    -- The status is DERIVED from the mismatches; stating it here too means no writer can store a
    -- TIED_OUT run that carries breaks, which is the one row ADR-0314 D3 must never serve.
    CONSTRAINT ck_snapshot_run_status_matches CHECK ((status = 'TIED_OUT') = (mismatch_count = 0))
);

CREATE INDEX idx_snapshot_run_as_of ON snapshot_run (as_of, recorded_at DESC);

CREATE TABLE snapshot_tie_out_mismatch (
    id               BIGSERIAL PRIMARY KEY,
    run_id           UUID NOT NULL REFERENCES snapshot_run (id),
    gl_account_code  VARCHAR(32),
    currency         CHAR(3) NOT NULL,
    -- Unconstrained NUMERIC: the tie-out is exact, so storage must not round what was compared.
    ledger_net       NUMERIC NOT NULL,
    positions_net    NUMERIC NOT NULL
);

CREATE INDEX idx_snapshot_tie_out_mismatch_run ON snapshot_tie_out_mismatch (run_id);

-- Contract-level (sub-ledger) or GL-level positions, in the trial-balance sign convention
-- (debit − credit). Bitemporal: valid_date is the business as-of, recorded_at when the engine
-- learned it.
CREATE TABLE snapshot_position (
    id               BIGSERIAL PRIMARY KEY,
    run_id           UUID NOT NULL REFERENCES snapshot_run (id),
    position_kind    VARCHAR(16) NOT NULL CHECK (position_kind IN ('SUB_LEDGER', 'GL_ACCOUNT')),
    gl_account_code  VARCHAR(32),
    gl_account_type  VARCHAR(16),
    currency         CHAR(3) NOT NULL,
    sub_account_id   UUID,
    amount           NUMERIC NOT NULL,
    valid_date       DATE NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_snapshot_position_kind_shape CHECK (
        (position_kind = 'SUB_LEDGER' AND sub_account_id IS NOT NULL)
        OR (position_kind = 'GL_ACCOUNT' AND sub_account_id IS NULL AND gl_account_code IS NOT NULL)
    )
);

CREATE INDEX idx_snapshot_position_run ON snapshot_position (run_id);

-- Central-bank fixing rates, one row per (source, fixing_date, currency). The primary key is the
-- idempotency key of the consumer: a redelivered event inserts nothing.
CREATE TABLE fx_fixing_rate (
    source          VARCHAR(16) NOT NULL,
    fixing_date     DATE NOT NULL,
    currency        CHAR(3) NOT NULL,
    quote_currency  CHAR(3) NOT NULL,
    rate_per_unit   NUMERIC NOT NULL CHECK (rate_per_unit > 0),
    rate_id         UUID NOT NULL,
    valid_from      TIMESTAMPTZ NOT NULL,
    valid_to        TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source, fixing_date, currency)
);
