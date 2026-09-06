-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- The risk-reporting gold pack behind ADR-0286 (issue #8943) — the first reports served through the
-- Admin UI's governed query registry.
--
-- WHY GOLD, AND WHY NOW. ADR-0286's rule is that the registry reads gold marts only, never bronze
-- and never silver directly: one definition of each business figure, living with the schema. Two of
-- the three questions below had NO gold answer — settled-volume-by-day and failures-by-day existed
-- only as per-caller SQL — so they are materialised here. The third (credit distress) deliberately
-- adds nothing: V11's gold_credit_quote_outcomes and gold_credit_consent_activation already answer
-- it, and the registry points at them as-is. A report pack that re-derives an existing gold view is
-- the two-definitions failure V5's header warns about, applied one layer up.
--
-- WHAT "RISK" MEANS HERE. Observable quantities only — volumes, failures, concentration, distress
-- signals — in the same posture V10 states for the credit profile: this pack computes no score, no
-- rating and no limit breach. The judgement (what is suspicious, what breaches a limit) belongs to
-- the owning services (aml-service, fraud-service, lending-service); the warehouse reports the
-- measurements those judgements are made from. A threshold a regulator or a policy owns never lives
-- in these views — the registry carries report parameters, so the same view serves every threshold
-- without a migration.
--
-- occurred_at throughout, never ingested_at (V10/V13 state why: a backfill must land in the period
-- it happened, or a reload silently rewrites a reported month).
-- ─────────────────────────────────────────────────────────────────────────────

-- ─────────────────────────────────────────────────────────────────────────────
-- The reporting-facing settled-transaction fact.
--
-- V13's silver_settled_transactions is the settlement join itself — an engineering object (which
-- event carried the amount, which carried the settlement time). ADR-0286 bars the registry from
-- reading silver, so the reporting fact is re-projected as gold with the same closed column list:
-- no payload passthrough, no counterparty name, no free-text message. A field added to the
-- transaction event later cannot arrive in a risk report by accident.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW openbank_analytics.gold_risk_settled_transactions AS
SELECT
    transaction_id,
    settled_at,
    toDate(settled_at) AS settled_day,
    settled_month,
    amount,
    currency_code,
    transaction_type,
    instruction_type,
    rail,
    source_account_id,
    target_account_id,
    initiated_by_party_id,
    initiated_at
FROM openbank_analytics.silver_settled_transactions;

-- ─────────────────────────────────────────────────────────────────────────────
-- Daily settled volume per currency and rail: the baseline a risk operator reads first.
--
-- Per currency, never summed across currencies: a total that mixes CZK and EUR is a number that
-- answers no question. rail is surfaced verbatim (V13 documents that it reads UNKNOWN for most
-- traffic today — a declared field the producers do not fill yet); grouping by it keeps that
-- visible instead of averaging it away.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW openbank_analytics.gold_risk_settlement_daily AS
SELECT
    settled_day              AS day,
    currency_code,
    rail,
    count()                  AS settled_count,
    sum(amount)              AS settled_amount,
    max(amount)              AS largest_amount,
    uniqExact(transaction_id) AS distinct_transactions
FROM openbank_analytics.gold_risk_settled_transactions
GROUP BY day, currency_code, rail;

-- ─────────────────────────────────────────────────────────────────────────────
-- Daily failed transactions. The failure side has no settled fact to join (a failed transaction
-- moves no money), so this reads bronze directly at the EVENT level — which ADR-0286's gold-only
-- rule exists to allow exactly here: the gold view IS the reduction, and no caller re-derives
-- "what counts as a failed transaction" for itself. TransactionFailed payload content is not
-- extracted because its shape is not yet relied on anywhere in-repo; counting the event is the
-- honest signal, and a reason breakdown is a producer change first.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW openbank_analytics.gold_risk_failures_daily AS
SELECT
    toDate(occurred_at)      AS day,
    count()                  AS failed_count,
    uniqExact(aggregate_id)  AS distinct_transactions
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'TRANSACTION'
  AND event_type = 'TransactionFailed'
GROUP BY day;

-- ─────────────────────────────────────────────────────────────────────────────
-- Platform-wide event volume per day and aggregate type. Not a risk signal by itself — it is the
-- freshness/completeness context every risk figure above needs: a day with zero settlement events
-- because the sink lagged reads identically to a day with no business, and only this view tells
-- them apart. Pure bronze columns (aggregate_type, event_type, occurred_at), so no payload shape
-- is assumed and a newly ingested topic appears here without a migration.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW openbank_analytics.gold_risk_event_volume_daily AS
SELECT
    toDate(occurred_at)      AS day,
    upper(aggregate_type)    AS aggregate_type,
    count()                  AS events,
    uniqExact(aggregate_id)  AS distinct_aggregates,
    max(occurred_at)         AS last_event_at
FROM openbank_analytics.bronze_events
GROUP BY day, aggregate_type;
