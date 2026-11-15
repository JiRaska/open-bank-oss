-- SPDX-License-Identifier: Apache-2.0
-- ADR-0371 slice 2: what the scheduled pack evaluation established, per party, and its hits.
--
-- Forward-only, additive: two new tables.
-- Rollback: DROP TABLE aml_monitoring_hit; DROP TABLE aml_monitoring_evaluation;
-- (nothing references them; dropping loses the record of what monitoring found).

-- Latest evaluation of one party under one pack content. The outcome has no "clear": NO_FINDING
-- says the rules did not match, INCOMPLETE says the pack could not be evaluated, and why.
CREATE TABLE aml_monitoring_evaluation (
    party_id      UUID         NOT NULL,
    pack_hash     VARCHAR(64)  NOT NULL,
    pack_id       VARCHAR(64)  NOT NULL,
    pack_revision INTEGER      NOT NULL,
    outcome       VARCHAR(16)  NOT NULL,
    failure       VARCHAR(128),
    input_hash    VARCHAR(64),
    evaluated_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (party_id, pack_hash),
    CONSTRAINT ck_aml_monitoring_evaluation_outcome CHECK (outcome IN ('NO_FINDING', 'FINDING', 'INCOMPLETE')),
    CONSTRAINT ck_aml_monitoring_evaluation_failure CHECK ((outcome = 'INCOMPLETE') = (failure IS NOT NULL))
);

-- One row per party, scenario and window: a repeat detection updates it instead of adding one.
CREATE TABLE aml_monitoring_hit (
    hit_id            UUID         PRIMARY KEY,
    party_id          UUID         NOT NULL,
    scenario_code     VARCHAR(64)  NOT NULL,
    window_start      TIMESTAMPTZ  NOT NULL,
    pack_id           VARCHAR(64)  NOT NULL,
    pack_revision     INTEGER      NOT NULL,
    pack_hash         VARCHAR(64)  NOT NULL,
    reason_code       VARCHAR(64)  NOT NULL,
    severity_code     VARCHAR(64)  NOT NULL,
    counter_snapshot  TEXT         NOT NULL,
    disposition       VARCHAR(16)  NOT NULL,
    case_id           UUID,
    detection_count   BIGINT       NOT NULL,
    first_detected_at TIMESTAMPTZ  NOT NULL,
    last_detected_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_aml_monitoring_hit_window UNIQUE (party_id, scenario_code, window_start),
    CONSTRAINT ck_aml_monitoring_hit_disposition CHECK (disposition IN ('SHADOW_RECORDED', 'CASE_OPENED')),
    CONSTRAINT ck_aml_monitoring_hit_case CHECK ((disposition = 'CASE_OPENED') = (case_id IS NOT NULL)),
    CONSTRAINT ck_aml_monitoring_hit_count CHECK (detection_count > 0)
);
CREATE INDEX idx_aml_monitoring_hit_party ON aml_monitoring_hit (party_id, last_detected_at);
