-- SPDX-License-Identifier: Apache-2.0
-- Preserve every committed change to the current exposure projection. This is an observation
-- history, NOT a regulator-ready business-date snapshot: the existing REST upsert supplies no
-- source-effective timestamp. A later feed must establish reference-date provenance and coverage
-- before using these rows to render a monthly return.
--
-- Rollback: export credit_exposure_observation, then DROP TRIGGER
-- credit_exposure_observe_changes ON credit_exposures; DROP FUNCTION
-- observe_credit_exposure_change(); DROP TABLE credit_exposure_observation. The current
-- credit_exposures table and the existing REST/list/return paths remain untouched.

CREATE TABLE credit_exposure_observation (
    version_id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    captured_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    event_kind             VARCHAR(16) NOT NULL
                           CHECK (event_kind IN ('BASELINE', 'INSERT', 'UPDATE', 'DELETE')),
    instrument_id          VARCHAR(64) NOT NULL,
    debtor_id              VARCHAR(64) NOT NULL,
    debtor_type            VARCHAR(16) NOT NULL,
    instrument_type        VARCHAR(24) NOT NULL,
    currency               VARCHAR(3) NOT NULL,
    committed_amount       NUMERIC(20, 2) NOT NULL,
    drawn_amount           NUMERIC(20, 2) NOT NULL,
    committed_amount_eur   NUMERIC(20, 2) NOT NULL,
    arrears_amount         NUMERIC(20, 2) NOT NULL,
    defaulted              BOOLEAN NOT NULL,
    origination_date       DATE NOT NULL
);

CREATE INDEX idx_credit_exposure_observation_instrument
    ON credit_exposure_observation (instrument_id, version_id DESC);

-- Flyway runs this PostgreSQL migration in one transaction. Block concurrent INSERT/UPDATE/DELETE
-- until the baseline and trigger both commit; otherwise a writer between the SELECT and trigger
-- creation could disappear from observation history. Reads remain available during the copy.
LOCK TABLE credit_exposures IN SHARE ROW EXCLUSIVE MODE;

-- The only fact available for pre-migration rows is their value at migration time. Do not
-- invent a historical source date from origination_date or updated_at.
INSERT INTO credit_exposure_observation (
    event_kind, instrument_id, debtor_id, debtor_type, instrument_type, currency,
    committed_amount, drawn_amount, committed_amount_eur, arrears_amount, defaulted,
    origination_date
)
SELECT 'BASELINE', instrument_id, debtor_id, debtor_type, instrument_type, currency,
       committed_amount, drawn_amount, committed_amount_eur, arrears_amount, defaulted,
       origination_date
FROM credit_exposures;

CREATE FUNCTION observe_credit_exposure_change() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    exposure credit_exposures%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        exposure := OLD;
    ELSE
        exposure := NEW;
    END IF;
    INSERT INTO credit_exposure_observation (
        event_kind, instrument_id, debtor_id, debtor_type, instrument_type, currency,
        committed_amount, drawn_amount, committed_amount_eur, arrears_amount, defaulted,
        origination_date
    ) VALUES (
        TG_OP, exposure.instrument_id, exposure.debtor_id, exposure.debtor_type,
        exposure.instrument_type, exposure.currency, exposure.committed_amount,
        exposure.drawn_amount, exposure.committed_amount_eur, exposure.arrears_amount,
        exposure.defaulted, exposure.origination_date
    );
    RETURN NULL;
END;
$$;

CREATE TRIGGER credit_exposure_observe_changes
AFTER INSERT OR UPDATE OR DELETE ON credit_exposures
FOR EACH ROW EXECUTE FUNCTION observe_credit_exposure_change();
