-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- The credit-lifecycle marts (#8893): what the ADR-0213 engine decided, and how the loan book is
-- staged under IFRS 9 over time.
--
-- WHAT THE WAREHOUSE HELD BEFORE THIS. Nothing about credit. `openbank.lending.events` is not in the
-- analytics-sink subscription, so none of the eleven lending event types has ever reached bronze.
-- The only credit rows are V11's funnel, which is the CUSTOMER-JOURNEY stream (consent, quote,
-- application started/abandoned) and carries no decision, no stage and no exposure — it says so in
-- its own header. So a vintage curve, a roll rate, a stage-migration matrix or a PD backtest was not
-- merely hard, it had no source.
--
-- THESE VIEWS ARE THEREFORE WRITTEN AHEAD OF THEIR DATA, AND RETURN ZERO ROWS UNTIL THE
-- SUBSCRIPTION LANDS. That is deliberate and it is the safe half: a view over an absent
-- aggregate_type is empty, whereas subscribing first would start writing rows that #8928 had not yet
-- made keyable. Sequence is subscription LAST — see the warning below.
--
-- WHY THE PRODUCER CHANGE COMES FIRST (#8928). `AnalyticsConsumer` resolves aggregate_type from the
-- payload, then an inference, then the TOPIC — and `openbank.lending.events` yields LENDING for every
-- type alike. It then resolves the id via `idForType(type, node)`, which has no LENDING branch and
-- falls through to `?: accountId ?: partyId`. Six of the eleven lending types carried partyId and
-- named no identity, so each would have landed keyed by the BORROWER: `bronze_events` is
-- ORDER BY (aggregate_type, aggregate_id, event_id) and `silver_current_state` groups by that pair,
-- so every loan of one borrower would collapse into a single aggregate. #8928 makes each event name
-- `aggregateType` = LOAN (or LOAN_APPLICATION) and its own `aggregateId`; these views read those
-- values and would silently mis-aggregate without it.
--
-- SYNTHETIC ACTIVITY IS EXCLUDED (V9 / ADR-0252). The synthetic customer fleet exercises real credit
-- journeys in production, so an unfiltered decision rate would be a blend of the bank's applicants
-- and the bank's own probes. Every view here filters `synthetic = 0`, the same defence V9 applied to
-- the baseline aggregates.
--
-- occurred_at THROUGHOUT, NEVER ingested_at. A backfill or a replayed dead letter must land in the
-- period it happened; keying on ingest would move a decision into the month it was re-read.
--
-- PII POSTURE. These views select a closed column list. `party_id` is a pseudonymous internal
-- identifier and is the only personal datum here; income, existing debt service, age, residency and
-- employment tenure are NOT on the wire and are not derivable from it. `reasons` and
-- `matched_rule_ids` are machine-readable policy codes, not free text. No `payload` passthrough, so
-- a field added to a lending event later cannot arrive in a mart by accident.
-- ─────────────────────────────────────────────────────────────────────────────

-- Every engine evaluation, one row each. The decision evidence ADR-0214 pins on the application, as
-- columns rather than as a JSON blob a reader has to re-parse per query.
CREATE OR REPLACE VIEW openbank_analytics.silver_credit_decisions AS
SELECT
    aggregate_id                                          AS loan_application_id,
    JSONExtractString(payload, 'partyId')                 AS party_id,
    occurred_at                                           AS decided_at,
    toStartOfDay(occurred_at)                             AS decided_day,
    toStartOfMonth(occurred_at)                           AS decided_month,
    JSONExtractString(payload, 'outcome')                 AS outcome,
    JSONExtractString(payload, 'priceBand')               AS price_band,
    -- Semicolon-free CSV as the producer writes it: "CODE:ruleId,CODE:-".
    JSONExtractString(payload, 'reasons')                 AS reasons,
    JSONExtractString(payload, 'matchedRuleIds')          AS matched_rule_ids,
    JSONExtractString(payload, 'policyVersions')          AS policy_versions,
    JSONExtractString(payload, 'inputSnapshotHash')       AS input_snapshot_hash,
    JSONExtractInt(payload, 'packVersion')                AS pack_version
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'LOAN_APPLICATION'
  AND event_type = 'credit.decision.evaluated'
  AND synthetic = 0;

-- Outcome mix per day. The headline the credit-risk console shows as a trend, over the whole history
-- rather than the console's capped window.
CREATE OR REPLACE VIEW openbank_analytics.gold_credit_decision_outcomes AS
SELECT
    decided_day                                    AS day,
    countIf(outcome = 'APPROVE')                   AS approved,
    countIf(outcome = 'REFER')                     AS referred,
    countIf(outcome = 'DECLINE')                   AS declined,
    count()                                        AS evaluated,
    uniqExact(party_id)                            AS parties_evaluated,
    countIf(outcome = 'APPROVE' AND price_band = 'PRIME')    AS approved_prime,
    countIf(outcome = 'APPROVE' AND price_band = 'STANDARD') AS approved_standard
FROM openbank_analytics.silver_credit_decisions
GROUP BY day;

-- The adverse-action Pareto: which reason code, and which rule, refuses most.
--
-- A decision carries its reasons as one CSV cell, so the row is exploded here rather than in every
-- query that asks. A fragment is "CODE:ruleId" with "-" for a reason no single rule produced.
CREATE OR REPLACE VIEW openbank_analytics.gold_credit_decision_reasons AS
SELECT
    decided_month                                                  AS month,
    outcome,
    splitByChar(':', reason)[1]                                    AS reason_code,
    if(splitByChar(':', reason)[2] IN ('', '-'), '', splitByChar(':', reason)[2]) AS rule_id,
    count()                                                        AS occurrences,
    uniqExact(loan_application_id)                                 AS applications
FROM openbank_analytics.silver_credit_decisions
ARRAY JOIN splitByChar(',', reasons) AS reason
WHERE reason != ''
GROUP BY month, outcome, reason_code, rule_id;

-- Which policy version decided what. A policy change is only defensible if its before-and-after is
-- visible, and `policy_versions` is the pinned table set for that exact evaluation.
CREATE OR REPLACE VIEW openbank_analytics.gold_credit_policy_versions AS
SELECT
    decided_month            AS month,
    policy_versions,
    pack_version,
    countIf(outcome = 'APPROVE')  AS approved,
    countIf(outcome = 'REFER')    AS referred,
    countIf(outcome = 'DECLINE')  AS declined,
    count()                       AS evaluated,
    min(decided_at)               AS first_seen,
    max(decided_at)               AS last_seen
FROM openbank_analytics.silver_credit_decisions
GROUP BY month, policy_versions, pack_version;

-- ─────────────────────────────────────────────────────────────────────────────
-- The loan book: disbursement as the vintage anchor, staging as the outcome.
-- ─────────────────────────────────────────────────────────────────────────────

-- One row per disbursed loan. This is the dimension the stage and ECL facts join to, and the reason
-- `loan.interest_accrued` needs no party of its own (#8928): the loan carries it.
CREATE OR REPLACE VIEW openbank_analytics.silver_loans AS
SELECT
    aggregate_id                                             AS loan_id,
    JSONExtractString(payload, 'partyId')                    AS party_id,
    occurred_at                                              AS disbursed_at,
    toStartOfMonth(occurred_at)                              AS vintage_month,
    JSONExtractString(payload, 'principal')                  AS principal_raw
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'LOAN'
  AND event_type = 'loan.disbursed'
  AND synthetic = 0;

-- Every IFRS 9 stage transition. The migration matrix a risk committee asks for is a GROUP BY over
-- this: previous_stage x new_stage per period.
CREATE OR REPLACE VIEW openbank_analytics.silver_loan_stage_transitions AS
SELECT
    aggregate_id                                       AS loan_id,
    JSONExtractString(payload, 'partyId')              AS party_id,
    occurred_at                                        AS changed_at,
    JSONExtractString(payload, 'period')               AS period,
    JSONExtractString(payload, 'previousStage')        AS previous_stage,
    JSONExtractString(payload, 'newStage')             AS new_stage,
    JSONExtractInt(payload, 'daysPastDue')             AS days_past_due
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'LOAN'
  AND event_type = 'loan.stage_changed'
  AND synthetic = 0;

-- Stage migration per reporting period. Deliberately keyed on the period the PROVISIONING CYCLE
-- stamped, not on the month the event landed: a cycle run late still belongs to its own period.
CREATE OR REPLACE VIEW openbank_analytics.gold_loan_stage_migration AS
SELECT
    period,
    previous_stage,
    new_stage,
    count()                    AS transitions,
    uniqExact(loan_id)         AS loans,
    uniqExact(party_id)        AS parties,
    -- A cure is as interesting as a deterioration, and a matrix that only counts one direction
    -- reads as a book that never recovers.
    countIf(new_stage > previous_stage) AS deteriorations,
    countIf(new_stage < previous_stage) AS cures
FROM openbank_analytics.silver_loan_stage_transitions
GROUP BY period, previous_stage, new_stage;

-- Expected credit loss per cycle. `delta` is what the ledger posted; `expectedCreditLoss` is the
-- level the loan sits at. Both are kept: a period's movement and its closing position answer
-- different questions, and summing deltas to reconstruct a level is how rounding drift enters.
CREATE OR REPLACE VIEW openbank_analytics.silver_loan_provisioning AS
SELECT
    aggregate_id                                                     AS loan_id,
    JSONExtractString(payload, 'partyId')                            AS party_id,
    JSONExtractString(payload, 'period')                             AS period,
    occurred_at                                                      AS provisioned_at,
    JSONExtractString(payload, 'stage')                              AS stage,
    toDecimal64OrNull(JSONExtractString(payload, 'expectedCreditLoss'), 2) AS expected_credit_loss,
    toDecimal64OrNull(JSONExtractString(payload, 'delta'), 2)        AS ecl_delta
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'LOAN'
  AND event_type = 'loan.provisioned'
  AND synthetic = 0;

-- ECL by period and stage. The coverage ratio a reader wants is ecl / exposure, and exposure is NOT
-- on this event — so it is not computed here rather than divided by something approximate. The
-- exposure column is the producer gap this view leaves visible instead of papering over.
CREATE OR REPLACE VIEW openbank_analytics.gold_loan_provisioning_by_stage AS
SELECT
    period,
    stage,
    uniqExact(loan_id)          AS loans,
    sum(expected_credit_loss)   AS expected_credit_loss,
    sum(ecl_delta)              AS ecl_movement
FROM openbank_analytics.silver_loan_provisioning
GROUP BY period, stage;

-- The vintage curve: loans grouped by the month they were disbursed, against the worst stage each
-- has reached since. A loan with no stage event has never migrated, which is Stage 1 by definition
-- of the producer — but it is reported as `never_migrated` rather than asserted as Stage 1, because
-- "no event" and "an event saying Stage 1" are different facts and only one of them was measured.
CREATE OR REPLACE VIEW openbank_analytics.gold_loan_vintage AS
SELECT
    l.vintage_month                                        AS vintage_month,
    count()                                                AS loans,
    countIf(worst_stage = '')                              AS never_migrated,
    countIf(worst_stage = 'STAGE_2')                       AS reached_stage_2,
    countIf(worst_stage = 'STAGE_3')                       AS reached_stage_3,
    countIf(max_dpd > 90)                                  AS ever_90_plus_dpd
FROM
(
    SELECT loan_id, party_id, vintage_month
    FROM openbank_analytics.silver_loans
) AS l
LEFT JOIN
(
    SELECT
        loan_id,
        max(new_stage)      AS worst_stage,
        max(days_past_due)  AS max_dpd
    FROM openbank_analytics.silver_loan_stage_transitions
    GROUP BY loan_id
) AS s
    ON s.loan_id = l.loan_id
GROUP BY vintage_month;
