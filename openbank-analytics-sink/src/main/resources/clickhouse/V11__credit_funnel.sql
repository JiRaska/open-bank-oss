-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- The ADR-0269 credit-journey funnel (rule 8's metrics).
--
-- WHAT THIS DELIBERATELY CANNOT ANSWER: "how many customers converted". There is no conversion
-- event upstream and there is no acceptance column here. The programme's metrics are
-- self-activation, opt-out-within-30-days, affordability declines, delinquency, and offers shown
-- without a prior customer action — a funnel built to optimise acceptance would quietly become the
-- thing ADR-0269 exists to prevent.
--
-- WHY A SEPARATE STREAM from the onboarding funnel: that one is an anonymous public write. These
-- events say "this person is looking at borrowing", so they ride an authenticated topic keyed by
-- party. The two must not be unioned into one view, or the anonymous stream's trust level would
-- silently become the credit stream's.

CREATE OR REPLACE VIEW openbank_analytics.gold_credit_funnel_events AS
SELECT
    JSONExtractString(payload, 'partyId') AS party_id,
    JSONExtractString(payload, 'step')    AS step,
    JSONExtractString(payload, 'action')  AS action,
    occurred_at
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'CREDIT_FUNNEL'
  AND JSONExtractString(payload, 'partyId') != '';

-- Self-activation, the headline number: of the people who ever opened the consent screen, how many
-- switched offers ON themselves. Deliberately NOT "how many were offered credit".
CREATE OR REPLACE VIEW openbank_analytics.gold_credit_consent_activation AS
SELECT
    toStartOfDay(occurred_at)                                        AS day,
    countIf(action = 'VIEWED' AND step = 'CONSENT')                  AS consent_screen_views,
    countIf(action = 'CONSENT_GRANTED')                              AS granted,
    countIf(action = 'CONSENT_WITHDRAWN')                            AS withdrawn,
    uniqExactIf(party_id, action = 'CONSENT_GRANTED')                AS parties_granted,
    uniqExactIf(party_id, action = 'CONSENT_WITHDRAWN')              AS parties_withdrawn
FROM openbank_analytics.gold_credit_funnel_events
GROUP BY day;

-- Pricing outcomes. QUOTE_SUPPRESSED is tracked as prominently as QUOTE_REQUESTED on purpose: the
-- distress floor refusing to price is a thing the bank must be able to SEE, not a silent branch.
CREATE OR REPLACE VIEW openbank_analytics.gold_credit_quote_outcomes AS
SELECT
    toStartOfDay(occurred_at)                        AS day,
    countIf(action = 'QUOTE_REQUESTED')              AS requested,
    countIf(action = 'QUOTE_SUPPRESSED')             AS suppressed,
    countIf(action = 'APPLICATION_STARTED')          AS applications_started,
    countIf(action = 'APPLICATION_ABANDONED')        AS applications_abandoned
FROM openbank_analytics.gold_credit_funnel_events
GROUP BY day;
