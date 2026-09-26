-- ─────────────────────────────────────────────────────────────────────────────
-- V17 — fix party attribution for TRANSACTION, and give the mechanism a health signal
--       (ADR-0282 phase 1, #8792 / #2891)
--
-- WHY THIS AND NOT THE PRODUCER CHANGE #2891 ASKS FOR. That line wants `partyId` denormalised onto
-- every ACCOUNT event. Three reasons not to:
--
--   1. ADR-0022 twice states the value that a later change be adoptable "without changing the
--      producer contract, because the bronze layer is already an append-only log". Bronze takes what
--      producers emit; relationships resolve in silver (V5, V12, V16). Denormalising a foreign
--      aggregate's field into every event inverts that.
--   2. Ownership is a property of the ACCOUNT aggregate, not of a balance change. Copied into each
--      event it freezes a point-in-time owner — right at emission, wrong for any query meaning "who
--      owns this now", with nothing afterwards to tell the two readings apart.
--   3. It would not have fixed the actual defect. Measured 2026-09-11, attribution through the
--      account map WORKS: `AccountStatusChanged` resolves to 9 distinct parties while carrying no
--      `partyId` at all.
--
-- WHAT THE ACTUAL DEFECTS WERE, and the coverage view below is what found the second one:
--
--   A. THE ACCOUNT MAP IS SILENTLY INCOMPLETE. Of the 29 accounts appearing in ACCOUNT events, 19
--      are in `silver_party_accounts`; the other 10 carry 334 unattributed events. The remedy is
--      #8905's `INITIAL_LOAD` backfill, which exists and is wired — it needs running, not more code.
--   B. TRANSACTION ATTRIBUTION HAS NEVER WORKED, in V12 or in V16 which copied its predicate.
--      Both claim `upper(aggregate_type) IN ('ACCOUNT', 'TRANSACTION')` and resolve ZERO transaction
--      rows, because BOTH halves of the predicate are wrong for that type:
--        * the payload leg reads `partyId`, and no transaction event carries one — measured 0 of
--          125, while 51 carry `initiatedByPartyId`;
--        * the account leg joins `pa.account_id = aggregate_id`, and a transaction's aggregate id is
--          the TRANSACTION id — measured 0 matches, while 9 rows' `sourceAccountId` matches a known
--          account.
--      A clause that reads as coverage and resolves nothing is worse than an absent one: every
--      party-scoped count over transactions has silently been zero since V12 shipped.
--
-- This migration fixes B and adds the signal that would have caught it.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── The attribution key, stated once ─────────────────────────────────────────
-- Three ways an event names its party, in precedence order. Kept as one expression per leg rather
-- than repeated across views, because the previous split is exactly how V12 and V16 drifted from
-- what they claimed: `partyId` (party/consent/account-creation events), `initiatedByPartyId`
-- (transactions — a different field name for the same idea), else ownership of the account the
-- event is about (`aggregate_id` for ACCOUNT rows, `sourceAccountId` for TRANSACTION rows, since a
-- transaction's own aggregate id is not an account).

CREATE OR REPLACE VIEW openbank_analytics.silver_party_events AS
-- Leg 1: the party is named in the payload, under either spelling.
SELECT
    if(
        JSONExtractString(s.payload, 'partyId') != '',
        JSONExtractString(s.payload, 'partyId'),
        JSONExtractString(s.payload, 'initiatedByPartyId')
    )                                       AS party_id,
    upper(s.aggregate_type)                 AS aggregate_type,
    s.aggregate_id,
    s.event_id,
    s.event_type,
    s.occurred_at,
    s.source_service,
    s.payload
FROM openbank_analytics.silver_current_state AS s
WHERE JSONExtractString(s.payload, 'partyId') != ''
   OR JSONExtractString(s.payload, 'initiatedByPartyId') != ''

UNION ALL

-- Leg 2: the party owns the account the event is about. The account id lives in a different place
-- per type, which is the half V12 got wrong: an ACCOUNT event IS about its aggregate, a TRANSACTION
-- event is about the account named in its payload.
--
-- The `= ''` guards on BOTH payload spellings are what keep the union disjoint. V12 guarded only
-- `partyId`, which was harmless only because its transaction leg matched nothing at all; with the
-- join fixed, omitting the `initiatedByPartyId` guard would double every attributed transaction —
-- and a doubled count reads as a busier customer, so the bug would be silent.
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
    ON pa.account_id = if(
        upper(s.aggregate_type) = 'TRANSACTION',
        JSONExtractString(s.payload, 'sourceAccountId'),
        s.aggregate_id
    )
WHERE upper(s.aggregate_type) IN ('ACCOUNT', 'TRANSACTION')
  AND JSONExtractString(s.payload, 'partyId') = ''
  AND JSONExtractString(s.payload, 'initiatedByPartyId') = '';

-- ── The same correction over history (V16's view) ────────────────────────────
-- V16 copied V12's predicate and inherited the same dead transaction clause. `valid_to` stays
-- unprojected for the reason V16 gives: it is a `leadInFrame` over the aggregate's own partition
-- and means nothing once rows are re-keyed by party.

CREATE OR REPLACE VIEW openbank_analytics.silver_party_activity AS
SELECT
    if(
        JSONExtractString(h.payload, 'partyId') != '',
        JSONExtractString(h.payload, 'partyId'),
        JSONExtractString(h.payload, 'initiatedByPartyId')
    )                                       AS party_id,
    upper(h.aggregate_type)                 AS aggregate_type,
    h.aggregate_id,
    h.aggregate_version,
    h.event_type,
    h.valid_from                            AS occurred_at,
    h.payload
FROM openbank_analytics.silver_history AS h
WHERE JSONExtractString(h.payload, 'partyId') != ''
   OR JSONExtractString(h.payload, 'initiatedByPartyId') != ''

UNION ALL

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
    ON pa.account_id = if(
        upper(h.aggregate_type) = 'TRANSACTION',
        JSONExtractString(h.payload, 'sourceAccountId'),
        h.aggregate_id
    )
WHERE upper(h.aggregate_type) IN ('ACCOUNT', 'TRANSACTION')
  AND JSONExtractString(h.payload, 'partyId') = ''
  AND JSONExtractString(h.payload, 'initiatedByPartyId') = '';

-- ── The health signal ────────────────────────────────────────────────────────
-- The gap was never a missing field; it was a missing NUMBER. The resolution mechanism had no health
-- signal, and an incomplete map reads exactly like a customer who has done nothing.
--
-- THE PREDICATE HERE IS DELIBERATELY THE SAME ONE THE VIEWS ABOVE USE. That is load-bearing, not
-- tidiness: a coverage view computing its own notion of "known owner" would report a gap the
-- attribution views do not have, or hide one they do, and the number would stop describing what it
-- names. If the attribution key changes, this changes with it — which is why they live in one file.
--
-- SCOPE IS ACCOUNT + TRANSACTION, the two types attributed through the account map. Widening it to
-- every type would count PARTY and CONSENT events, which carry their own `partyId` by construction,
-- diluting the ratio toward 100% while the accounts stream stayed broken — the failure mode of
-- every aggregate metric this repo has been bitten by.
--
-- WHEN `aggregates_unattributed` IS NON-ZERO: request a backfill with `source=INITIAL_LOAD`
-- (#8905). This view is what makes that a response to evidence rather than something an operator
-- has to remember.

CREATE OR REPLACE VIEW openbank_analytics.gold_party_attribution_coverage AS
SELECT
    aggregate_type,
    count()                                                          AS events,
    uniqExact(aggregate_id)                                          AS aggregates,
    countIf(owner_known)                                             AS events_attributed,
    uniqExactIf(aggregate_id, owner_known)                           AS aggregates_attributed,
    count() - countIf(owner_known)                                   AS events_unattributed,
    uniqExact(aggregate_id) - uniqExactIf(aggregate_id, owner_known) AS aggregates_unattributed
FROM (
    SELECT
        upper(b.aggregate_type) AS aggregate_type,
        b.aggregate_id          AS aggregate_id,
        (
            JSONExtractString(b.payload, 'partyId') != ''
            OR JSONExtractString(b.payload, 'initiatedByPartyId') != ''
            OR if(
                upper(b.aggregate_type) = 'TRANSACTION',
                JSONExtractString(b.payload, 'sourceAccountId'),
                b.aggregate_id
            ) IN (SELECT account_id FROM openbank_analytics.silver_party_accounts)
        )                       AS owner_known
    FROM openbank_analytics.bronze_events AS b
    WHERE upper(b.aggregate_type) IN ('ACCOUNT', 'TRANSACTION')
)
GROUP BY aggregate_type;
