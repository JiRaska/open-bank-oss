-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- The credit LIFECYCLE views (issue #8893). V10/V11 already cover the pre-decision journey — a
-- party's cash-flow profile and the consent/quote funnel; neither carries a decision, an outcome, a
-- stage or an exposure. This is the other half: what happened to an application once it was decided,
-- and what happens to a loan afterwards. It reduces `openbank.lending.events`, added to
-- analytics-sink's subscription in the same change (application.yaml `topics:` + the matching Read
-- ACL in kafka-analytics-sink-mtls.yaml) — a view defined here is inert until that subscription is
-- actually live and the ACL lets the consumer read it.
--
-- Money fields on this stream serialise as Kotlin `Money.toString()` — "<amount> <currency>", e.g.
-- "1000.00 CZK" — never a bare number (`Money.kt`). Every monetary column below is parsed by
-- splitting on the space and kept grouped by the currency it came with; summing across an
-- unfiltered currency column would silently add CZK to EUR (the same rule `LoanStateSummary`/
-- `MoneyTotal` state on the OLTP side: money is per-currency, never one number).
--
-- PII posture: these views carry `party_id`, the same pseudonymous join key `silver_party_accounts`
-- and `gold_party_credit_profile` (V10) already key on — never a name, address or raw income figure.
-- The bronze payload behind them is masked at the sink (`PayloadMasker`) before it ever reaches
-- ClickHouse; nothing here reverses that.

-- ─────────────────────────────────────────────────────────────────────────────
-- Decision outcomes over time (credit.decision.evaluated, aggregate_type=LOAN_APPLICATION).
--
-- outcome is one of APPROVE / REFER / DECLINE (OriginationDecisionService.outcomeName) — never a
-- boolean, so a REFER cannot be miscounted as either a grant or a refusal.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW openbank_analytics.gold_credit_decision_outcomes AS
SELECT
    toStartOfDay(occurred_at)                AS day,
    JSONExtractString(payload, 'outcome')    AS outcome,
    JSONExtractString(payload, 'priceBand')  AS price_band,
    count()                                  AS decisions,
    uniqExact(aggregate_id)                  AS applications
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'LOAN_APPLICATION'
  AND event_type = 'credit.decision.evaluated'
GROUP BY day, outcome, price_band;

-- ─────────────────────────────────────────────────────────────────────────────
-- IFRS 9 stage migration (loan.stage_changed, aggregate_type=LOAN).
--
-- One row per observed transition, not per loan-day — a stage migration MATRIX (roll rate) is built
-- by grouping this on (previous_stage, new_stage), which is why both are kept distinct columns
-- rather than folded into "worsened"/"improved" here.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW openbank_analytics.gold_loan_stage_migration AS
SELECT
    toStartOfDay(occurred_at)                              AS day,
    JSONExtractString(payload, 'previousStage')            AS previous_stage,
    JSONExtractString(payload, 'newStage')                 AS new_stage,
    count()                                                AS transitions,
    uniqExact(aggregate_id)                                AS loans,
    avg(JSONExtractInt(payload, 'daysPastDue'))            AS avg_days_past_due
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'LOAN'
  AND event_type = 'loan.stage_changed'
GROUP BY day, previous_stage, new_stage;

-- ─────────────────────────────────────────────────────────────────────────────
-- Per-loan, per-period provisioning snapshot (loan.provisioned, aggregate_type=LOAN).
--
-- `argMax` by occurred_at, not a raw sum: provisionedPayload carries the loan's CURRENT ECL as of
-- `period`, and delivery is at-least-once, so more than one event for the same (loan, period) must
-- collapse to the latest rather than double-count. `delta` is summed on its own — it is the
-- MOVEMENT (`snapshot.expectedCreditLoss - prior`), correct to add across re-deliveries of the same
-- period only because a duplicate delivery repeats the identical value rather than compounding it.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW openbank_analytics.silver_loan_provisioning_snapshots AS
SELECT
    aggregate_id                                                              AS loan_id,
    JSONExtractString(payload, 'period')                                      AS period,
    argMax(JSONExtractString(payload, 'stage'), occurred_at)                  AS stage,
    argMax(
        toDecimal64OrNull(splitByChar(' ', JSONExtractString(payload, 'expectedCreditLoss'))[1], 2),
        occurred_at
    )                                                                          AS expected_credit_loss,
    argMax(splitByChar(' ', JSONExtractString(payload, 'expectedCreditLoss'))[2], occurred_at)
                                                                               AS currency,
    max(occurred_at)                                                          AS as_of
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'LOAN'
  AND event_type = 'loan.provisioned'
GROUP BY loan_id, period;

CREATE OR REPLACE VIEW openbank_analytics.gold_loan_ecl_by_period AS
SELECT
    period,
    stage,
    currency,
    uniqExact(loan_id)         AS loans,
    sum(expected_credit_loss)  AS total_expected_credit_loss
FROM openbank_analytics.silver_loan_provisioning_snapshots
GROUP BY period, stage, currency;

-- ─────────────────────────────────────────────────────────────────────────────
-- Vintage: disbursement cohort × reporting period × stage (loan.disbursed joined to the
-- provisioning snapshots above).
--
-- WHAT THIS DELIBERATELY CANNOT ANSWER, same spirit as V11's credit-funnel caveat: a cohort's curve
-- is only as long as `loan.provisioned` has actually fired for it, so a cohort disbursed this month
-- has no later-period rows yet — that is the true, still-unfolding state of the book, not a gap in
-- the view.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW openbank_analytics.silver_loan_disbursements AS
SELECT
    aggregate_id                                                          AS loan_id,
    JSONExtractString(payload, 'partyId')                                 AS party_id,
    toStartOfMonth(occurred_at)                                           AS disbursement_month,
    toDecimal64OrNull(splitByChar(' ', JSONExtractString(payload, 'principal'))[1], 2)
                                                                           AS principal_amount,
    splitByChar(' ', JSONExtractString(payload, 'principal'))[2]          AS currency
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'LOAN'
  AND event_type = 'loan.disbursed';

CREATE OR REPLACE VIEW openbank_analytics.gold_loan_vintage AS
SELECT
    d.disbursement_month        AS vintage_month,
    s.period                    AS period,
    s.stage                     AS stage,
    d.currency                  AS currency,
    uniqExact(d.loan_id)        AS loans,
    sum(d.principal_amount)     AS disbursed_principal,
    sum(s.expected_credit_loss) AS total_expected_credit_loss
FROM openbank_analytics.silver_loan_disbursements AS d
INNER JOIN openbank_analytics.silver_loan_provisioning_snapshots AS s
    ON s.loan_id = d.loan_id
GROUP BY vintage_month, period, stage, currency;
