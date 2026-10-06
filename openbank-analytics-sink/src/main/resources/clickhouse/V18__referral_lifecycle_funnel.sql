-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
--
-- #7194: count only authoritative referral lifecycle events. The consumer allowlists the
-- payload before bronze persistence; no party IDs, invitation token or click enters this view.
-- A missing/null programVersion remains NULL, never joined to today's catalogue. uniqExactIf
-- protects the counts from at-least-once delivery before ReplacingMergeTree merges parts.

CREATE OR REPLACE VIEW openbank_analytics.gold_referral_lifecycle_funnel AS
SELECT
    JSONExtractString(payload, 'programId') AS program_id,
    nullIf(JSONExtractInt(payload, 'programVersion'), 0) AS program_version,
    uniqExactIf(event_id, event_type = 'Qualified') AS qualified_events,
    uniqExactIf(event_id, event_type = 'RewardRequested') AS reward_requested_events,
    uniqExactIf(event_id, event_type = 'RewardOutcome' AND JSONExtractString(payload, 'outcome') = 'ACCEPTED') AS accepted_outcomes,
    uniqExactIf(event_id, event_type = 'RewardOutcome' AND JSONExtractString(payload, 'outcome') = 'REJECTED') AS rejected_outcomes,
    uniqExactIf(event_id, event_type = 'RewardOutcome' AND JSONExtractString(payload, 'outcome') = 'REVERSED') AS reversed_outcomes,
    max(occurred_at) AS last_observed_at
FROM openbank_analytics.bronze_events
WHERE aggregate_type = 'REFERRAL'
  AND source_service = 'referral-service'
  AND event_type IN ('Qualified', 'RewardRequested', 'RewardOutcome')
  AND JSONExtractString(payload, 'programId') != ''
GROUP BY program_id, program_version;
