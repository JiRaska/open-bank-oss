-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- Fold the silver layer's own reduction, issue #4632 (the #4553 backfill follow-up).
--
-- #4553 measured bronze holding both `ACCOUNT`/`Account` and `Transaction`/`Consent`-only spellings
-- for the same domains, and #4576 stopped it growing by normalising at ingest — but rows written
-- before that fix keep their producer's original spelling forever. #4632 was opened to plan a bronze
-- backfill for those historical rows and closes with a different answer: the actual symptom #4553
-- named — "one account has two current-state rows" — is a property of the SILVER reduction's own
-- GROUP BY, not of the bronze rows themselves. Folding it here resolves the symptom for every past
-- and future row without ever touching bronze (ADR-0022: a 10-year, append-only log of record).
--
-- WHY NOT A BRONZE BACKFILL INSTEAD. Two paths were considered and rejected:
--   1. An ALTER TABLE ... UPDATE mutation on bronze_events.aggregate_type. Rejected: it edits the
--      log of record in place — exactly what the retention floor and the WORM tamper-evidence
--      mechanism (ADR-0023 F1/F2) exist to make visible if it ever happens, not to enable it.
--   2. A CORRECTION re-ingest through the existing BackfillService/BackfillRequest pipeline.
--      Rejected on two independent grounds: that pipeline's only wired BackfillSource is
--      NoOpBackfillSource today (no real OLTP replay source exists for any domain), and even with
--      one, its own contract — "re-ingestion is safe... dedupe on eventId + last-writer-wins on
--      aggregateVersion, so a backfill... converges to the same state" — assumes aggregate_type is
--      UNCHANGED between the old and replayed row. Bronze's ORDER BY is
--      (aggregate_type, aggregate_id, event_id): a replayed row under the corrected spelling is a
--      DIFFERENT sort key, so ReplacingMergeTree would insert it ALONGSIDE the original rather than
--      collapsing onto it — the split would not close, it would grow by one more row per event.
--
-- Verified read-only against the sandbox before writing this: folding the GROUP BY (not editing
-- bronze) drops distinct (type, id) groups from 237 to 232 — exactly the 5 previously-split accounts
-- collapsing to one row each, each keeping the true latest state (the highest aggregate_version
-- across BOTH of its old spellings, via argMax — same resolution silver already does per group, now
-- computed over one group instead of two).
--
-- All three: CREATE OR REPLACE, not edits to V1's `CREATE VIEW IF NOT EXISTS` bodies — those have
-- never been superseded before now, and IF NOT EXISTS is a no-op on a warehouse that already has the
-- view (the trap V4's own header documents). Downstream is unaffected: every reader added since
-- #4553 already folds on its own read (silver_party_accounts #4520, the reconciliation reader
-- #4604/#4618, the Customer 360 route's `.toLowerCase()`) — this view now simply agrees with them at
-- the source instead of relying on every reader to repeat the fold.

CREATE OR REPLACE VIEW openbank_analytics.silver_current_state AS
SELECT
    upper(b.aggregate_type)                                                       AS aggregate_type,
    b.aggregate_id,
    argMax(b.event_id, (b.aggregate_version, b.occurred_at, b.ingested_at))       AS event_id,
    max(b.aggregate_version)                                                      AS aggregate_version,
    argMax(b.event_type, (b.aggregate_version, b.occurred_at, b.ingested_at))     AS event_type,
    argMax(b.occurred_at, (b.aggregate_version, b.occurred_at, b.ingested_at))    AS occurred_at,
    argMax(b.source_service, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS source_service,
    argMax(b.schema_version, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS schema_version,
    argMax(b.payload, (b.aggregate_version, b.occurred_at, b.ingested_at))        AS payload
FROM openbank_analytics.bronze_events AS b
GROUP BY upper(b.aggregate_type), b.aggregate_id;

-- SCD2 history: PARTITION BY (not GROUP BY), so folding needs the SAME upper() in both the output
-- column and the window's PARTITION BY — folding only the output would leave a mixed-case account's
-- version sequence split across two windows, each computing its own (wrong) valid_from/valid_to.
CREATE OR REPLACE VIEW openbank_analytics.silver_history AS
SELECT
    upper(aggregate_type)                                                 AS aggregate_type,
    aggregate_id,
    aggregate_version,
    event_type,
    occurred_at                                                           AS valid_from,
    leadInFrame(occurred_at) OVER (
        PARTITION BY upper(aggregate_type), aggregate_id
        ORDER BY aggregate_version, occurred_at, ingested_at
        ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING
    )                                                                     AS valid_to,
    payload
FROM openbank_analytics.bronze_events;

CREATE OR REPLACE VIEW openbank_analytics.silver_as_of AS
SELECT
    upper(b.aggregate_type)                                                    AS aggregate_type,
    b.aggregate_id,
    argMax(b.event_id, (b.aggregate_version, b.occurred_at, b.ingested_at))    AS event_id,
    max(b.aggregate_version)                                                   AS aggregate_version,
    argMax(b.event_type, (b.aggregate_version, b.occurred_at, b.ingested_at))  AS event_type,
    argMax(b.occurred_at, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS occurred_at,
    argMax(b.payload, (b.aggregate_version, b.occurred_at, b.ingested_at))     AS payload
FROM openbank_analytics.bronze_events AS b
WHERE b.occurred_at <= {t:DateTime64(3, 'UTC')}
GROUP BY upper(b.aggregate_type), b.aggregate_id;
