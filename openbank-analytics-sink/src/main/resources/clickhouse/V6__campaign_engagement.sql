-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Privacy-safe campaign engagement mart (issue #4535).
--
-- campaignId, stepOrder and channel are not accepted on the mobile client's authority. The
-- customer edge removes client values and campaign-service resolves them from the opaque PUSH
-- interactionRef and its SENT send-log row. No party id, content text or interaction reference is
-- projected here. Handoff/adapter acceptance remains in campaign-service; this view only counts
-- what the app or an authoritative business-event producer actually observed.
--
-- uniqExactIf(event_id, ...) makes the aggregate safe under Kafka at-least-once delivery even
-- before ReplacingMergeTree has merged duplicate bronze parts.

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
WHERE aggregate_type = 'ENGAGEMENT'
  AND JSONExtractString(payload, 'campaignId') != ''
  AND JSONHas(payload, 'stepOrder')
  AND JSONExtractString(payload, 'channel') IN ('PUSH', 'BANNER')
GROUP BY campaign_id, step_order, channel;
