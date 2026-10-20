-- ADR-0347 (issue #13437): the money thresholds of the amount-based fraud rules become versioned,
-- effective-dated, four-eyes-activated data instead of Kotlin constants.
--
-- One row per proposed threshold-set version. The scoring path reads ONLY rows in state EXECUTED,
-- choosing the HIGHEST version whose active_from is not after "now" (so a lower version scheduled
-- for a later date can never reactivate over a higher one). A row
-- reaches EXECUTED only through FraudThresholdActivationService.decide with a checker other than
-- the proposer; ck_fraud_threshold_set_four_eyes restates that in the schema so not even a direct
-- write can record a self-approved set.
--
-- thresholds_json: {"<rule-key>": {"<ISO 4217>": "<decimal string>", ...}, ...} — amounts as
-- strings, never JSON numbers, so no float ever touches a money figure.
--
-- SEED — version 1 is EXACTLY the six constants FraudRuleEngine.kt carried before this migration
-- (LargeSingleTransactionReviewRule, VelocityH1HighValueReviewRule, NewPayeeHighAmountReviewRule),
-- so every verdict is byte-identical across the rollout. Its four-eyes is the money-path PR review
-- those constants already passed (two approvals, ADR-0030), recorded as such rather than as a
-- runtime checker that never existed. active_from = the moment this migration runs: the set is in
-- force from deploy, which is when the constants it replaces stopped being read. content_hash is
-- FraudThresholdSet.contentHash over this payload; FraudThresholdSeedIT asserts the two agree.
--
-- fraud_scores.threshold_set_version pins, on every audit row, which set a verdict was evaluated
-- against (NULL = none active, the fail-closed path; also NULL on rows written before this
-- migration, which were all evaluated against the constants version 1 reproduces).
--
-- Rollback: only before any version other than 1 has been activated, and together with the code
-- that reads it (the previous release scores from constants and ignores both objects):
--   ALTER TABLE fraud_scores DROP COLUMN threshold_set_version;
--   DROP TABLE fraud_threshold_set;
-- After a later version has been activated the table is the audit record of who changed a fraud
-- control and when — archive it, do not drop it.

CREATE TABLE fraud_threshold_set (
    id              UUID         PRIMARY KEY,
    version         INTEGER      NOT NULL CHECK (version >= 1),
    state           VARCHAR(16)  NOT NULL,
    thresholds_json TEXT         NOT NULL,
    content_hash    VARCHAR(64)  NOT NULL,
    effective_from  TIMESTAMPTZ,
    active_from     TIMESTAMPTZ,
    proposed_by     VARCHAR(128) NOT NULL,
    proposed_at     TIMESTAMPTZ  NOT NULL,
    decided_by      VARCHAR(128),
    decided_at      TIMESTAMPTZ,
    decision_reason VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_fraud_threshold_set_state
        CHECK (state IN ('PROPOSED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'EXECUTED')),
    CONSTRAINT ck_fraud_threshold_set_four_eyes
        CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT ck_fraud_threshold_set_active_iff_executed
        CHECK ((state = 'EXECUTED') = (active_from IS NOT NULL))
);

-- A version number is claimed once among live rows; a REJECTED/WITHDRAWN version may be re-proposed.
CREATE UNIQUE INDEX uq_fraud_threshold_set_live_version
    ON fraud_threshold_set (version)
    WHERE state IN ('PROPOSED', 'APPROVED', 'EXECUTED');

CREATE INDEX idx_fraud_threshold_set_active
    ON fraud_threshold_set (version DESC, active_from)
    WHERE state = 'EXECUTED';

INSERT INTO fraud_threshold_set (
    id, version, state, thresholds_json, content_hash, effective_from, active_from,
    proposed_by, proposed_at, decided_by, decided_at, decision_reason
) VALUES (
    '01920000-0000-7000-8000-000000000347', 1, 'EXECUTED',
    '{"large-single-transaction":{"CZK":"500000","EUR":"20000"},'
        || '"velocity-h1-amount":{"CZK":"1000000","EUR":"40000"},'
        || '"new-payee-high-amount":{"CZK":"250000","EUR":"10000"}}',
    '928c0ecd3ef7bb12281210d5f33515de41936bb0bddb612368e139436ac1ccd4',
    NULL, now(),
    'flyway:V9__fraud_threshold_set', now(), 'money-path-pr-review', now(),
    'Seed: the first-pass calibration previously hard-coded in FraudRuleEngine.kt, unchanged (ADR-0347, #13437)'
);

ALTER TABLE fraud_scores ADD COLUMN threshold_set_version INTEGER;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
