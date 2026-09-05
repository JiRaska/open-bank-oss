-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- The per-party event profile ADR-0282 phase 1 asks for (issue #8792).
--
-- WHAT WAS ACTUALLY MISSING. ADR-0210 put Customer 360 in the silver layer, and V5 gave the
-- account-to-party key one definition. What no object gave one definition to is the question one
-- level up: WHICH ROWS BELONG TO A PARTY. The Customer 360 BFF answers it inline, in TypeScript,
-- on every request — `payload.partyId = P` OR an ACCOUNT/TRANSACTION row whose aggregate_id is one
-- of P's accounts — then pulls up to 5000 rows and folds them in the route handler.
--
-- That predicate IS the isolation boundary, in the same sense V5's header means it: a caller that
-- widens its own copy shows another customer's events. V5 collapsed one such copy and the route's
-- own comment records the lesson ("a resolution with two definitions has two answers"), but the
-- scoping around the view stayed behind in the caller. `silver_party_events` is that scoping, named
-- once, so a second consumer — the Lipa earn evaluation, the segment DSL, the AI advisor — inherits
-- the boundary instead of re-deriving it.
--
-- WHY silver_current_state AND NOT bronze. Deliberately the same source the Customer 360 route
-- reads today, so this view is a refactor of an existing answer rather than a new one with a
-- different meaning. Silver is the latest event per (aggregate_type, aggregate_id): a profile built
-- on it describes current state, which is what a 360 view is for. Anything needing the full history
-- (cash-flow medians, cadence) must read bronze, and V10's monthly-flows view already does exactly
-- that — the two are different questions, not two answers to one.
--
-- THE ACCOUNT LEG READS V5, WHICH READS BRONZE, AND THAT ASYMMETRY IS LOAD-BEARING. An account's
-- latest silver row is typically a status or balance event carrying no partyId, so resolving the
-- account key from silver would drop exactly the parties with the most account activity. V5 already
-- makes that choice and states it; this view inherits it by delegating rather than repeating it.
--
-- WHAT IT DELIBERATELY DOES NOT DO. It computes no financial-health measure, no segment membership
-- and no score. ADR-0220 D5's rule applies to this phase: a rewards programme rendered from
-- fabricated profiles is worse than a missing feature. Every column below is a count or a timestamp
-- that is directly observable in the rows; nothing is imputed for a party with thin data, and a
-- party with no events has no row here rather than a row of zeros.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE OR REPLACE VIEW openbank_analytics.silver_party_events AS
SELECT
    JSONExtractString(s.payload, 'partyId') AS party_id,
    -- Folded once here so no downstream view repeats it. silver_current_state already upper()s its
    -- output; the fold is restated rather than assumed, because a caller that inherits case-folding
    -- by accident breaks the day the source view changes and nothing says why.
    upper(s.aggregate_type)                 AS aggregate_type,
    s.aggregate_id,
    s.event_id,
    s.event_type,
    s.occurred_at,
    s.source_service,
    s.payload
FROM openbank_analytics.silver_current_state AS s
WHERE JSONExtractString(s.payload, 'partyId') != ''

UNION ALL

-- The account leg: rows the party owns through an account rather than through a payload key. The
-- `NOT IN` on the payload key is what keeps the union disjoint — without it a TRANSACTION row that
-- carries BOTH a partyId and an owned account id appears twice, and every count below doubles for
-- exactly the best-instrumented events.
SELECT
    pa.party_id                             AS party_id,
    upper(s.aggregate_type)                 AS aggregate_type,
    s.aggregate_id,
    s.event_id,
    s.event_type,
    s.occurred_at,
    s.source_service,
    s.payload
FROM openbank_analytics.silver_current_state AS s
INNER JOIN openbank_analytics.silver_party_accounts AS pa
    ON pa.account_id = s.aggregate_id
WHERE upper(s.aggregate_type) IN ('ACCOUNT', 'TRANSACTION')
  AND JSONExtractString(s.payload, 'partyId') = '';

-- ─────────────────────────────────────────────────────────────────────────────
-- The profile itself: the fold the Customer 360 route performs per request, computed once.
--
-- The per-domain breakdown is a SECOND view (one row per party per domain) rather than a column per
-- domain, because the domain set is open: a newly ingested topic must widen the profile without a
-- migration, and a fixed column list would report zero for a domain that merely had no column yet.
-- That is the sentinel-default failure shape — indistinguishable from a real zero.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW openbank_analytics.gold_party_event_profile AS
SELECT
    party_id,
    count()                                    AS events_observed,
    uniqExact(aggregate_id)                    AS aggregates_observed,
    -- upper() restated at the comparison even though silver_party_events folds it: inheriting a
    -- fold is an assumption that breaks silently the day the source view changes, which is what the
    -- aggregate-type-case-fold gate exists to prevent. upper() of an already-folded value is free.
    uniqExactIf(aggregate_id, upper(aggregate_type) = 'ACCOUNT')      AS accounts_observed,
    uniqExact(aggregate_type)                  AS domains_observed,
    min(occurred_at)                           AS first_seen_at,
    max(occurred_at)                           AS last_seen_at,
    -- Recency in days, computed here so every consumer agrees on the clock. NOT seeded from a
    -- sentinel: a party with no events has no row at all, so there is no boot-time value that could
    -- read as decades of staleness the way a registration-time gauge does.
    dateDiff('day', max(occurred_at), now())   AS days_since_last_event,
    arraySort(groupUniqArray(aggregate_type))  AS domains
FROM openbank_analytics.silver_party_events
GROUP BY party_id;

-- One row per (party, domain): the `byDomain` map the Customer 360 route builds in the handler,
-- including the latest event per domain that the route derives from row order. argMax rather than
-- "the first row of a DESC scan", because the ordering guarantee is a property of the query the
-- caller happens to write, and this must hold for every caller.
CREATE OR REPLACE VIEW openbank_analytics.gold_party_domain_activity AS
SELECT
    party_id,
    aggregate_type,
    count()                                                   AS events_observed,
    argMax(event_type, occurred_at)                           AS last_event_type,
    max(occurred_at)                                          AS last_occurred_at
FROM openbank_analytics.silver_party_events
GROUP BY party_id, aggregate_type;
