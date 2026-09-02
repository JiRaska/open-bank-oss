-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- The credit profile behind ADR-0269 (issue #6215).
--
-- ONE definition, for the same reason V5 gives for the party key: three consumers need these
-- numbers — the creditworthiness assessment (which must be reproducible for an audit), the
-- customer's own financial-health view, and the AI advisor. Two implementations that disagree is
-- worse than one that is wrong, because only the second is detectable.
--
-- WHY IT LIVES HERE. ADR-0210 put Customer 360 in the silver layer rather than in a new service:
-- the topics are already ingested and the reduction already exists. A credit profile is the same
-- shape of question asked of the same rows.
--
-- WHAT IT DELIBERATELY DOES NOT DO. It computes no score, no rating and no decision. It reports
-- observable quantities — money in, money out, how steady, how long the cover lasts. The judgement
-- is lending-service's (ADR-0213), and keeping the two apart is what lets the thresholds move
-- without the measurements moving.

-- ─────────────────────────────────────────────────────────────────────────────
-- Per-party monthly cash flows.
--
-- A transaction is INBOUND for a party when the party owns the target account and OUTBOUND when it
-- owns the source. Both legs are counted from the same event, which is why the two halves are
-- unioned rather than joined: an internal transfer between two of the customer's OWN accounts is
-- then correctly both — it is not income, and the netting in the profile below removes it.
--
-- occurred_at, never ingested_at: a backfilled month must land in the month it happened, or a
-- reload silently rewrites someone's income history.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW openbank_analytics.silver_party_monthly_flows AS
SELECT
    party_id,
    month,
    sum(inbound)  AS inbound_amount,
    sum(outbound) AS outbound_amount,
    count()       AS movement_count
FROM
(
    SELECT
        pa.party_id                                                        AS party_id,
        toStartOfMonth(e.occurred_at)                                      AS month,
        toDecimal64(JSONExtractString(e.payload, 'amount'), 2)             AS inbound,
        toDecimal64('0', 2)                                                AS outbound
    FROM openbank_analytics.bronze_events AS e
    INNER JOIN openbank_analytics.silver_party_accounts AS pa
        ON pa.account_id = JSONExtractString(e.payload, 'targetAccountId')
    WHERE upper(e.aggregate_type) = 'TRANSACTION'
      AND JSONExtractString(e.payload, 'targetAccountId') != ''
      AND JSONExtractString(e.payload, 'amount') != ''

    UNION ALL

    SELECT
        pa.party_id                                                        AS party_id,
        toStartOfMonth(e.occurred_at)                                      AS month,
        toDecimal64('0', 2)                                                AS inbound,
        toDecimal64(JSONExtractString(e.payload, 'amount'), 2)             AS outbound
    FROM openbank_analytics.bronze_events AS e
    INNER JOIN openbank_analytics.silver_party_accounts AS pa
        ON pa.account_id = JSONExtractString(e.payload, 'sourceAccountId')
    WHERE upper(e.aggregate_type) = 'TRANSACTION'
      AND JSONExtractString(e.payload, 'sourceAccountId') != ''
      AND JSONExtractString(e.payload, 'amount') != ''
)
GROUP BY party_id, month;

-- ─────────────────────────────────────────────────────────────────────────────
-- The profile itself: the last six whole months, excluding the current partial one.
--
-- WHY SIX. Long enough for a quarterly pattern to show and for one unusual month not to dominate;
-- short enough that a job change is visible rather than averaged away.
--
-- WHY THE CURRENT MONTH IS EXCLUDED. A month in progress always looks like a collapse in income —
-- on the 3rd, salary has not arrived. Including it would make every profile read worst on the day
-- most customers open the app.
--
-- income_monthly is the MEDIAN inbound, not the mean: one bonus or one incoming transfer from
-- savings should not raise what the bank believes a customer earns every month.
--
-- volatility_ratio is the standard deviation of monthly net over the median inbound. It is a ratio,
-- not a currency amount, so it compares across income levels — 5,000 of swing means something very
-- different on 20,000 a month than on 200,000.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW openbank_analytics.gold_party_credit_profile AS
SELECT
    party_id,
    count()                                                     AS months_observed,
    quantileExact(0.5)(inbound_amount)                          AS income_monthly,
    quantileExact(0.5)(outbound_amount)                         AS outflow_monthly,
    quantileExact(0.5)(inbound_amount - outbound_amount)        AS net_monthly,
    -- Guard the divisor: a party with no observed inbound has an undefined ratio, and 0 would read
    -- as "perfectly stable" — the most flattering possible answer for the least-known customer.
    if(quantileExact(0.5)(inbound_amount) > 0,
       stddevPop(inbound_amount - outbound_amount) / quantileExact(0.5)(inbound_amount),
       NULL)                                                    AS volatility_ratio,
    sum(movement_count)                                         AS movements_observed
FROM openbank_analytics.silver_party_monthly_flows
WHERE month >= toStartOfMonth(now()) - INTERVAL 6 MONTH
  AND month <  toStartOfMonth(now())
GROUP BY party_id;
