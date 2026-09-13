-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0220 D3 (gamification), first slice — issue #3701. Two tables:
--
--   rewards_hub_membership: one current-state row per party (party_id IS the primary key —
--   see RewardsHubMembershipEntity's KDoc for why an app-assigned id is safe here, unlike most of
--   this service's other tables).
--
--   gamification_award: an append-only ledger of every points award, with a unique
--   (party_id, challenge_id) index — ONE award per party per challenge, ever. Deliberately not
--   (party_id, challenge_id, correlation_event_id): a challenge in this slice's catalogue is a
--   one-time completion, and every HTTP POST to /api/v1/surfaces/events creates a brand-new
--   engagement_event row with its own id, so keying on the triggering event's id would let the SAME
--   real-world achievement be reported twice and paid out twice (caught by GamificationOutboxIT's
--   "posting the same conversion twice awards only once", which failed against the first, wider key).
--   correlation_event_id is still stored, for audit ("which event triggered this specific award"),
--   but is not part of the uniqueness constraint. No FK to engagement_event: outbox dispatch and
--   read paths must not depend on that row still existing under any future retention policy, same
--   reasoning campaign-service's attribution tables already use.
--
-- Rollback: DROP TABLE gamification_award; DROP TABLE rewards_hub_membership;

CREATE TABLE rewards_hub_membership (
    party_id UUID        NOT NULL PRIMARY KEY,
    state    VARCHAR(16) NOT NULL,
    since    TIMESTAMPTZ NOT NULL
);

CREATE TABLE gamification_award (
    id                   UUID         NOT NULL PRIMARY KEY,
    party_id             UUID         NOT NULL,
    challenge_id         VARCHAR(64)  NOT NULL,
    earn_source_id       VARCHAR(64)  NOT NULL,
    points               INTEGER      NOT NULL,
    rule_version         VARCHAR(16)  NOT NULL,
    correlation_event_id UUID         NOT NULL,
    occurred_at          TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX ux_gamification_award_idempotency
    ON gamification_award (party_id, challenge_id);

CREATE INDEX ix_gamification_award_party ON gamification_award (party_id);
