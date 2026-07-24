-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- In-app screen-feedback gold marts (ADR-0192).
--
-- The qualitative counterpart to the onboarding funnel (V2): the funnel says WHERE users drop,
-- these views say WHY. Same shape as V2 — no new table, the events land in the generic
-- bronze_events log (aggregate_type = 'SCREEN_FEEDBACK', one row per submission, keyed on the
-- support-quotable reference). Applied by the same operator step as V1/V2, all IF NOT EXISTS.
--
-- Event grammar (payload JSON, produced by customer-edge FeedbackPublisher):
--   reference        FB-xxxxxxxxxxxx, the handle the customer can quote to support
--   partyId          from the bearer token, never client-asserted
--   screenId         nav route the feedback is about (e.g. 'payments/new')
--   category         ∈ BUG, IDEA, CONFUSING
--   comment          free-text (personal data — see the erasure note below)
--   screenshotKey    object-store key of the PNG, or absent; the IMAGE ITSELF IS NEVER HERE
--   screenshotStatus ∈ NONE (text-only), STORED, STORE_FAILED
--
-- GDPR (ADR-0192): comments and screenshots are personal data. party_id is projected below
-- precisely so an erasure request can find both the rows and — via screenshot_key — the objects
-- to delete. The S3 objects additionally expire on their own after 90 days (bucket lifecycle).

-- Normalised, typed projection of the raw feedback events. Everything else builds on this.
CREATE VIEW IF NOT EXISTS openbank_analytics.gold_screen_feedback AS
SELECT
    aggregate_id                                        AS reference,
    occurred_at,
    toDate(occurred_at)                                 AS day,
    JSONExtractString(payload, 'partyId')               AS party_id,
    JSONExtractString(payload, 'screenId')              AS screen_id,
    JSONExtractString(payload, 'category')              AS category,
    JSONExtractString(payload, 'comment')               AS comment,
    JSONExtractString(payload, 'platform')              AS platform,
    JSONExtractString(payload, 'appVersion')            AS app_version,
    JSONExtractString(payload, 'screenshotKey')         AS screenshot_key,
    JSONExtractString(payload, 'screenshotStatus')      AS screenshot_status,
    JSONExtractUInt(payload, 'screenshotBytes')         AS screenshot_bytes
FROM openbank_analytics.bronze_events
WHERE aggregate_type = 'SCREEN_FEEDBACK';

-- WHICH SCREENS HURT: volume per screen and category, newest activity first. This is the view the
-- product board reads to rank what to fix next.
CREATE VIEW IF NOT EXISTS openbank_analytics.gold_screen_feedback_by_screen AS
SELECT
    screen_id,
    category,
    count()                     AS submissions,
    countIf(screenshot_key != '') AS with_screenshot,
    min(occurred_at)            AS first_seen,
    max(occurred_at)            AS last_seen
FROM openbank_analytics.gold_screen_feedback
GROUP BY screen_id, category;

-- VOLUME OVER TIME per platform and app version, so a spike can be attributed to a release.
CREATE VIEW IF NOT EXISTS openbank_analytics.gold_screen_feedback_daily AS
SELECT
    day,
    category,
    platform,
    app_version,
    count()                        AS submissions,
    uniqExact(party_id)            AS parties,
    uniqExact(screen_id)           AS screens
FROM openbank_analytics.gold_screen_feedback
GROUP BY day, category, platform, app_version;

-- PIPELINE HEALTH: how often a screenshot was promised but not stored. A non-zero STORE_FAILED
-- count means object storage is misconfigured or unreachable (in sandbox: the bucket / Pod Identity
-- association is not provisioned yet) — the submission survived, the image did not.
CREATE VIEW IF NOT EXISTS openbank_analytics.gold_screen_feedback_screenshot_health AS
SELECT
    day,
    screenshot_status,
    count()                     AS submissions,
    sum(screenshot_bytes)       AS total_bytes
FROM openbank_analytics.gold_screen_feedback
GROUP BY day, screenshot_status;
