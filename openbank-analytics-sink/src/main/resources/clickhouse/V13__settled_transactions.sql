-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- The post-settlement transaction fact ADR-0282 phase 1 asks for (issue #8792).
--
-- WHAT THE ISSUE SAID, AND WHAT THE WAREHOUSE SAYS. #8792 states that the only transaction fact in
-- analytics is the initiated event, that it carries no rail, and that it is emitted before the money
-- moves. Measured 2026-09-05, two of those three are no longer true: THREE transaction event kinds
-- are ingested — TransactionInitiated (63), TransactionCompleted (47), TransactionFailed (13) — and
-- the initiated payload does carry `rail`, alongside amount, currencyCode, instructionType, type,
-- source/target account and initiatedByPartyId.
--
-- WHAT IS ACTUALLY MISSING IS THE OPPOSITE SHAPE. The post-settlement event EXISTS and is
-- content-free: TransactionCompleted carries only a referenceNumber. So "did it settle" and "what
-- settled" live in two different events, and every consumer that wants a settled amount has to know
-- to join them. Nothing did, which is why the fact read as absent.
--
-- Both events are keyed by the transaction's own aggregate_id, so the join is exact rather than
-- heuristic. Verified before writing this: all 47 completed transactions join back to an initiated
-- event carrying an amount, 47 of 47.
--
-- SETTLEMENT TIME COMES FROM THE COMPLETED EVENT, THE ECONOMICS FROM THE INITIATED ONE. Mixing them
-- is the whole point: a fact stamped with the initiation time would put a payment in the wrong day
-- and, for a month-boundary settlement, the wrong reporting period. occurred_at throughout, never
-- ingested_at — a backfill must land in the period it happened.
--
-- WHAT IS STILL GENUINELY MISSING, AND IS NOT PAPERED OVER HERE. Category, MCC and merchant are
-- absent from every transaction payload and cannot be derived: no view can invent them, and this one
-- does not try. `rail` is present but reads UNKNOWN for 55 of 63 initiated events, so it is a
-- declared field with no producer filling it — a column that exists and lies is worse than one that
-- is missing, so `rail` is surfaced verbatim rather than defaulted, and a consumer can see UNKNOWN
-- for what it is. Those three fields plus a populated rail are the producer change that remains.
--
-- NEVER THE COUNTERPARTY NAME AND NEVER THE FREE-TEXT MESSAGE. #8792 requires the enrichment to be
-- designed minimal rather than filtered afterwards, and this view selects a closed column list: no
-- `payload` passthrough, so a field added to the event later cannot arrive here by accident.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE OR REPLACE VIEW openbank_analytics.silver_settled_transactions AS
SELECT
    c.aggregate_id                                          AS transaction_id,
    c.occurred_at                                           AS settled_at,
    toStartOfMonth(c.occurred_at)                           AS settled_month,
    toDecimal64OrNull(JSONExtractString(i.payload, 'amount'), 2) AS amount,
    JSONExtractString(i.payload, 'currencyCode')            AS currency_code,
    JSONExtractString(i.payload, 'type')                    AS transaction_type,
    JSONExtractString(i.payload, 'instructionType')         AS instruction_type,
    -- Surfaced verbatim. UNKNOWN is what the producer wrote, and it must stay distinguishable from
    -- a rail this view failed to read.
    JSONExtractString(i.payload, 'rail')                    AS rail,
    JSONExtractString(i.payload, 'sourceAccountId')         AS source_account_id,
    JSONExtractString(i.payload, 'targetAccountId')         AS target_account_id,
    JSONExtractString(i.payload, 'initiatedByPartyId')      AS initiated_by_party_id,
    i.occurred_at                                           AS initiated_at
FROM
(
    SELECT aggregate_id, occurred_at
    FROM openbank_analytics.bronze_events
    WHERE upper(aggregate_type) = 'TRANSACTION' AND event_type = 'TransactionCompleted'
) AS c
INNER JOIN
(
    SELECT aggregate_id, occurred_at, payload
    FROM openbank_analytics.bronze_events
    WHERE upper(aggregate_type) = 'TRANSACTION' AND event_type = 'TransactionInitiated'
) AS i
    ON i.aggregate_id = c.aggregate_id;

-- ─────────────────────────────────────────────────────────────────────────────
-- Per-party settled spend, from the account side rather than the initiator side.
--
-- WHY NOT initiated_by_party_id. It is populated on 50 of 63 initiated events, so keying on it
-- silently drops a fifth of the traffic — and drops it as "this party spent less", which is the
-- flattering direction and therefore the dangerous one for anything that rewards behaviour.
-- Ownership through V5's account key covers every transaction with a known account, and both legs
-- are unioned so an internal transfer between two of the customer's own accounts appears as both,
-- exactly as V10's monthly flows already treats it.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW openbank_analytics.gold_party_settled_spend AS
SELECT
    party_id,
    settled_month,
    countIf(direction = 'OUT')                 AS outbound_count,
    sumIf(amount, direction = 'OUT')           AS outbound_amount,
    countIf(direction = 'IN')                  AS inbound_count,
    sumIf(amount, direction = 'IN')            AS inbound_amount,
    uniqExact(transaction_id)                  AS settled_transactions
FROM
(
    SELECT pa.party_id AS party_id, t.settled_month, t.amount, t.transaction_id, 'OUT' AS direction
    FROM openbank_analytics.silver_settled_transactions AS t
    INNER JOIN openbank_analytics.silver_party_accounts AS pa ON pa.account_id = t.source_account_id

    UNION ALL

    SELECT pa.party_id AS party_id, t.settled_month, t.amount, t.transaction_id, 'IN' AS direction
    FROM openbank_analytics.silver_settled_transactions AS t
    INNER JOIN openbank_analytics.silver_party_accounts AS pa ON pa.account_id = t.target_account_id
)
GROUP BY party_id, settled_month;
