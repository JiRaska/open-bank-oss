-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Consumer sweep, issue #4604 (the #4553 follow-up).
--
-- Three views compared aggregate_type against an unfolded literal: gold_onboarding_funnel_events
-- (V2), gold_screen_feedback (V4), gold_campaign_engagement (V6). None has ever seen a mixed-case
-- row for its own type on the sandbox — each is written by exactly one producer, and the mixed-case
-- split #4553 measured was ACCOUNT/Account and TRANSACTION/Transaction only. This is the same
-- correction as #4576 applied at ingest and #4520 applied to the party key: fold, don't trust the
-- producer's spelling, because a rename is one commit away from a literal silently matching nothing
-- — which is exactly the failure #4553 measured on a Grafana tile that had read 0 for its whole life.
--
-- CREATE OR REPLACE for all three, regardless of how each was originally declared. V2's
-- gold_onboarding_funnel_events is `CREATE VIEW IF NOT EXISTS` and has never been superseded —
-- editing V2's own body in place would be a no-op on a warehouse that already has the view (V4's own
-- header documents this trap). Views carry no data, so redefining the NAME here fully supersedes
-- every prior definition of it, on both a fresh cluster and an existing one; V2/V4/V6's own text is
-- left untouched as the historical record of how the schema arrived here.

CREATE OR REPLACE VIEW openbank_analytics.gold_onboarding_funnel_events AS
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
WHERE upper(aggregate_type) = 'ONBOARDING_FUNNEL';

CREATE OR REPLACE VIEW openbank_analytics.gold_screen_feedback AS
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
    JSONExtractString(payload, 'osVersion')             AS os_version,
    JSONExtractString(payload, 'locale')                AS locale,
    JSONExtractString(payload, 'theme')                 AS theme,
    JSONExtractString(payload, 'sessionId')             AS session_id,
    JSONExtractString(payload, 'screenshotKey')         AS screenshot_key,
    JSONExtractString(payload, 'screenshotStatus')      AS screenshot_status,
    JSONExtractUInt(payload, 'screenshotBytes')         AS screenshot_bytes
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'SCREEN_FEEDBACK';

CREATE OR REPLACE VIEW openbank_analytics.gold_campaign_engagement AS
SELECT
    JSONExtractString(payload, 'campaignId') AS campaign_id,
    JSONExtractUInt(payload, 'stepOrder')     AS step_order,
    JSONExtractString(payload, 'channel')    AS channel,
    uniqExactIf(event_id, event_type = 'EngagementEvent.IMPRESSION') AS impressions,
    uniqExactIf(event_id, event_type = 'EngagementEvent.CLICK')      AS clicks,
    uniqExactIf(event_id, event_type = 'EngagementEvent.DISMISS')    AS dismissals,
    min(occurred_at) AS first_observed_at,
    max(occurred_at) AS last_observed_at
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'ENGAGEMENT'
  AND JSONExtractString(payload, 'campaignId') != ''
  AND JSONHas(payload, 'stepOrder')
  AND JSONExtractString(payload, 'channel') IN ('PUSH', 'BANNER')
GROUP BY campaign_id, step_order, channel;
