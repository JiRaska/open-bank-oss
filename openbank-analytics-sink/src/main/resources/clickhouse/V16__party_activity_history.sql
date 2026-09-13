-- ─────────────────────────────────────────────────────────────────────────────
-- V16 — party activity over HISTORY, not current state (ADR-0282 phase 1, #8792 / #2891)
--
-- WHY THIS EXISTS, and why the obvious diagnosis was wrong. `silver_party_events` (V12) attributes
-- events to a party two ways: the payload's own `partyId`, or — for rows that carry none — a join
-- to `silver_party_accounts` on the account id. That join works: measured 2026-09-11,
-- `AccountStatusChanged` appears in V12's view against 9 distinct parties while carrying no
-- `partyId` of its own. So "ACCOUNT events have no party linkage" is NOT the reason party activity
-- cannot be queried, and #2891's producer change would not on its own fix it.
--
-- The reason is the BASIS. Both legs of V12 read `silver_current_state`, which keeps only the
-- LATEST event per aggregate, so 19 accounts yield at most 19 ACCOUNT rows. Measured on the same
-- warehouse: `silver_party_events` holds 19 ACCOUNT rows across two event types, while
-- `silver_history` holds 437 ACCOUNT rows — `BALANCE_UPDATED` (205), `HOLD_PLACED` (98) and
-- `HOLD_RELEASED` (97) exist and are simply not reachable through a current-state view, whoever
-- they belong to.
--
-- This view is the same attribution over `silver_history`. Nothing is imputed and nothing is
-- aggregated here: one row per historical event per owning party, so a caller can count, window or
-- cadence it without this file deciding which of those is wanted (ADR-0220 D5 — a rewards
-- programme rendered from fabricated profiles is worse than a missing feature).
--
-- WHAT IT IS NOT. It is not a replacement for `silver_party_events`: "what is the party's current
-- state" and "what has the party done" are different questions, and a history view answers the
-- second badly if used for the first (an account closed yesterday still has 200 balance rows).
-- V12 stays the current-state answer; this is the activity answer.
--
-- GRAIN, AND A TRAP IN IT. One row per bronze event per owning party. `aggregate_version` is
-- projected because it is meaningful for some aggregates, but it MUST NOT be used as a sequence:
-- measured 2026-09-11, all 205 `BALANCE_UPDATED` events in the warehouse carry `aggregate_version`
-- = 0 while having 205 distinct `event_id`s, across only 21 distinct (aggregate_id, version) pairs.
-- So (party, aggregate, version, event_type) is NOT a unique key here, and a caller that treats it
-- as one collapses real activity. `silver_history` does not project `event_id`, so a caller needing
-- a per-event key must join back to `bronze_events`; counting and time-windowing need none.
--
-- The `valid_to` column of `silver_history` is deliberately NOT projected. It is a
-- `leadInFrame(...)` over the aggregate's own partition — the interval during which THAT row was
-- the current state — and it means nothing once rows are re-keyed by party: two accounts owned by
-- one party interleave, so a party-scoped `valid_to` would look like a validity interval while
-- being an artefact of the partition it came from.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE OR REPLACE VIEW openbank_analytics.silver_party_activity AS
-- The payload leg: the party is named in the event itself.
SELECT
    JSONExtractString(h.payload, 'partyId') AS party_id,
    -- `silver_history` already folds this (V8), and the fold is restated rather than inherited:
    -- a caller that gets case-folding by accident breaks the day the source view changes, and
    -- nothing in the failure says why (#4604, and V12 states the same reasoning in place).
    upper(h.aggregate_type)                 AS aggregate_type,
    h.aggregate_id,
    h.aggregate_version,
    h.event_type,
    h.valid_from                            AS occurred_at,
    h.payload
FROM openbank_analytics.silver_history AS h
WHERE JSONExtractString(h.payload, 'partyId') != ''

UNION ALL

-- The account leg: rows the party owns through an account rather than through a payload key.
-- The `= ''` guard is what keeps the union DISJOINT — without it a TRANSACTION row carrying both a
-- `partyId` and an owned account id appears twice, and every count a caller builds doubles for
-- exactly the best-instrumented events. V12 carries the same guard for the same reason; the bug it
-- prevents is silent, since a doubled count looks like a busier customer.
SELECT
    pa.party_id                             AS party_id,
    upper(h.aggregate_type)                 AS aggregate_type,
    h.aggregate_id,
    h.aggregate_version,
    h.event_type,
    h.valid_from                            AS occurred_at,
    h.payload
FROM openbank_analytics.silver_history AS h
INNER JOIN openbank_analytics.silver_party_accounts AS pa
    ON pa.account_id = h.aggregate_id
WHERE upper(h.aggregate_type) IN ('ACCOUNT', 'TRANSACTION')
  AND JSONExtractString(h.payload, 'partyId') = '';
