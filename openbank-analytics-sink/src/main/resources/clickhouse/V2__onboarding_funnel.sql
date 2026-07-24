-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Onboarding-funnel gold marts (ADR-0069 Phase 2).
--
-- These views sit over the generic bronze_events log and are what the admin cockpit's onboarding
-- conversion board queries. No new table: the funnel events land in bronze like every other event
-- (aggregate_type = 'ONBOARDING_FUNNEL', one row per app-emitted transition, keyed on the
-- pseudonymous onboarding session id). Applied by the same operator step as V1, all IF NOT EXISTS.
--
-- Event grammar (payload JSON, produced by customer-edge OnboardingFunnelPublisher):
--   step   ∈ WELCOME, IDENTITY, EMAIL, AGREEMENT, PASSKEY, SIGN   (ordered)
--   action ∈ STEP_VIEWED, STEP_COMPLETED, HOLD_STARTED, HOLD_ABANDONED,
--            KYC_METHOD_CHOSEN, SIGN_ATTEMPT, SIGN_SUCCESS, SIGN_FAIL

-- Normalised, typed projection of the raw funnel events. Everything else builds on this.
CREATE VIEW IF NOT EXISTS openbank_analytics.gold_onboarding_funnel_events AS
SELECT
    aggregate_id                                  AS session_id,
    occurred_at,
    toDate(occurred_at)                           AS day,
    JSONExtractString(payload, 'step')            AS step,
    JSONExtractString(payload, 'action')          AS action,
    JSONExtractString(payload, 'kycMethod')       AS kyc_method,
    JSONExtractString(payload, 'reason')          AS reason,
    JSONExtractString(payload, 'platform')        AS platform,
    JSONExtractString(payload, 'appVersion')      AS app_version
FROM openbank_analytics.bronze_events
WHERE aggregate_type = 'ONBOARDING_FUNNEL';

-- Stable step ordinal so the funnel can be walked in order (and drop-off computed between adjacent
-- steps) regardless of the string values. A session is "cohorted" to the day it was first seen.
CREATE VIEW IF NOT EXISTS openbank_analytics.gold_onboarding_step_index AS
SELECT
    step,
    multiIf(
        step = 'WELCOME',   1,
        step = 'IDENTITY',  2,
        step = 'EMAIL',     3,
        step = 'AGREEMENT', 4,
        step = 'PASSKEY',   5,
        step = 'SIGN',      6,
        99) AS step_ordinal
FROM (SELECT DISTINCT step FROM openbank_analytics.gold_onboarding_funnel_events);

-- FUNNEL WIDTH per day: distinct sessions that VIEWED and that COMPLETED each step. The admin board
-- reads this directly and computes step->step conversion and drop-off % from the viewed counts.
CREATE VIEW IF NOT EXISTS openbank_analytics.gold_onboarding_funnel_daily AS
SELECT
    day,
    step,
    multiIf(
        step = 'WELCOME',   1,
        step = 'IDENTITY',  2,
        step = 'EMAIL',     3,
        step = 'AGREEMENT', 4,
        step = 'PASSKEY',   5,
        step = 'SIGN',      6,
        99)                                                     AS step_ordinal,
    uniqExactIf(session_id, action = 'STEP_VIEWED')             AS sessions_viewed,
    -- The SIGN step never emits STEP_COMPLETED — its terminal success signal is SIGN_SUCCESS
    -- (a signature can fail, unlike the earlier steps' unconditional completion). Count that as the
    -- SIGN step's completion so a signed agreement shows as "completed" in the funnel / conversion.
    uniqExactIf(session_id, action = 'STEP_COMPLETED' OR (step = 'SIGN' AND action = 'SIGN_SUCCESS')) AS sessions_completed,
    uniqExactIf(session_id, action = 'HOLD_ABANDONED')          AS hold_abandons
FROM openbank_analytics.gold_onboarding_funnel_events
GROUP BY day, step;

-- TIME ON STEP: per session+step, seconds between first view and first completion. The admin board
-- runs quantile(0.5)(seconds_on_step) over this for the median dwell time, split by step. Only
-- sessions that both viewed and completed the step contribute (a still-open step has no duration).
CREATE VIEW IF NOT EXISTS openbank_analytics.gold_onboarding_step_durations AS
SELECT
    session_id,
    step,
    toDate(minIf(occurred_at, action = 'STEP_VIEWED'))         AS day,
    dateDiff(
        'second',
        minIf(occurred_at, action = 'STEP_VIEWED'),
        minIf(occurred_at, action = 'STEP_COMPLETED'))         AS seconds_on_step
FROM openbank_analytics.gold_onboarding_funnel_events
WHERE action IN ('STEP_VIEWED', 'STEP_COMPLETED')
GROUP BY session_id, step
HAVING countIf(action = 'STEP_VIEWED') > 0
   AND countIf(action = 'STEP_COMPLETED') > 0
   AND seconds_on_step >= 0;

-- SIGN OUTCOMES: the tail everyone cares about — attempts vs successes vs failures at the final
-- framework-agreement signature, plus the machine reason for each failure so the board can rank why.
CREATE VIEW IF NOT EXISTS openbank_analytics.gold_onboarding_sign_outcomes AS
SELECT
    day,
    countIf(action = 'SIGN_ATTEMPT')                           AS attempts,
    countIf(action = 'SIGN_SUCCESS')                           AS successes,
    countIf(action = 'SIGN_FAIL')                              AS failures
FROM openbank_analytics.gold_onboarding_funnel_events
WHERE step = 'SIGN'
GROUP BY day;

CREATE VIEW IF NOT EXISTS openbank_analytics.gold_onboarding_sign_fail_reasons AS
SELECT
    day,
    if(reason = '', 'unknown', reason)                         AS reason,
    count()                                                    AS failures
FROM openbank_analytics.gold_onboarding_funnel_events
WHERE step = 'SIGN' AND action = 'SIGN_FAIL'
GROUP BY day, reason;

-- KYC METHOD SPLIT: which identity path prospects pick, and how it correlates with finishing.
CREATE VIEW IF NOT EXISTS openbank_analytics.gold_onboarding_kyc_method_daily AS
SELECT
    day,
    if(kyc_method = '', 'unknown', kyc_method)                 AS kyc_method,
    uniqExact(session_id)                                      AS sessions
FROM openbank_analytics.gold_onboarding_funnel_events
WHERE action = 'KYC_METHOD_CHOSEN'
GROUP BY day, kyc_method;
