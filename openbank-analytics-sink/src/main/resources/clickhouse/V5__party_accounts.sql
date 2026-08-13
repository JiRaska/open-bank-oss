-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- The party key (ADR-0210 D2).
--
-- D2 calls account→party resolution "the real work" of the Customer 360 and "the only new
-- persistence" the ADR adds, materialising "as a ClickHouse view alongside the existing silver
-- views". It never landed: the resolution shipped as an inline CTE inside one caller
-- (openbank-admin-ui's /api/customer-360/[partyId] route). This migration puts it where D2 says it
-- belongs, and that route now selects from it (issue #4511).
--
-- WHY IT MUST HAVE ONE DEFINITION. The ADR rejects "query bronze_events directly instead of the
-- silver views" because "every caller would re-derive the reduction, and the first one to write it
-- slightly differently produces a different current state". That applies to the party key with a
-- sharper failure mode: this resolution IS the isolation boundary of the Customer 360, so a caller
-- that widens its own copy shows another customer's accounts and transactions.
--
-- WHY IT READS bronze_events, NOT silver_current_state. Silver keeps only the LATEST event per
-- aggregate, and an account's latest event is typically BALANCE_UPDATED, whose payload carries
-- accountId but no partyId. Ownership is carried by the event that established it (account opened),
-- which only the full history has. Verified against the sandbox: silver holds no ACCOUNT row with a
-- partyId at all.
--
-- WHY upper(). aggregate_type is stored uppercase in bronze (PARTY, ACCOUNT, ONBOARDING_FUNNEL).
-- The route's first version compared against lowercase literals and matched nothing — and a filter
-- that finds no rows is indistinguishable from a party that owns no accounts, so nothing failed, the
-- page just showed less. The fold is load-bearing, not defensive style.
--
-- WHY party_id != ''. JSONExtractString returns '' for a missing key, so every ACCOUNT event whose
-- payload has no partyId would otherwise become a row owned by the empty party — and a caller that
-- forgets to validate its input would join to all of them at once. The Customer 360 route cannot
-- reach that case (its UUID regex is the injection boundary), which makes this latent rather than
-- live; a shared view must not hand the footgun to the next caller.
--
-- CREATE OR REPLACE, not an edit to V1: V1's statements are all `CREATE … IF NOT EXISTS`, so editing
-- that file changes nothing on a database where the objects already exist — it would look applied in
-- git and silently do nothing in ClickHouse. Same reasoning V4 documents.

CREATE OR REPLACE VIEW openbank_analytics.silver_party_accounts AS
SELECT DISTINCT
    JSONExtractString(payload, 'partyId') AS party_id,
    aggregate_id                          AS account_id
FROM openbank_analytics.bronze_events
WHERE upper(aggregate_type) = 'ACCOUNT'
  AND JSONExtractString(payload, 'partyId') != '';
