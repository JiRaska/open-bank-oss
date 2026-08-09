-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0220 D1/D2 — engagement events and their outbox. Rollback: DROP TABLE engagement_event;
-- DROP TABLE engagement_outbox; (no dependents; nothing else in this service reads these tables).

CREATE TABLE engagement_event (
    id          UUID         NOT NULL PRIMARY KEY,
    party_id    UUID         NOT NULL,
    content_id  VARCHAR(128) NOT NULL,
    slot        VARCHAR(32)  NOT NULL,
    type        VARCHAR(16)  NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL
);

-- Backs recentForPartyAndSlot (DismissalRule evaluation) and impressionsInWindow (the
-- ContactPolicyGate counter) — both filter on party_id (+ slot for the former) and occurred_at.
CREATE INDEX idx_engagement_event_party_slot_occurred
    ON engagement_event (party_id, slot, occurred_at DESC);
CREATE INDEX idx_engagement_event_party_type_occurred
    ON engagement_event (party_id, type, occurred_at DESC);

-- Same shape as account_outbox (#1201): claimed_at supports the atomic
-- FOR UPDATE SKIP LOCKED claim query so two concurrently running dispatcher pods (an Argo
-- Rollouts canary window runs old and new simultaneously) can never publish the same row twice.
CREATE TABLE engagement_outbox (
    id            BIGSERIAL PRIMARY KEY,
    event_id      UUID NOT NULL UNIQUE,
    aggregate_id  UUID NOT NULL,
    event_type    VARCHAR(128) NOT NULL,
    payload       TEXT NOT NULL,
    status        VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    sent_at       TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at    TIMESTAMPTZ
);

CREATE INDEX idx_engagement_outbox_status_created_at ON engagement_outbox(status, created_at ASC);
CREATE INDEX idx_engagement_outbox_aggregate_id ON engagement_outbox(aggregate_id);

-- Hibernate Reactive + PanacheEntity allocate ids from a sequence named "<table>_seq"
-- (allocationSize 50) — BIGSERIAL above only creates "engagement_outbox_id_seq", so every
-- INSERT via persist() would fail at runtime with relation "engagement_outbox_seq" does not
-- exist. Same defect fixed for account/party/notification (account-service V9). Repo
-- convention: unquoted, lowercase, INCREMENT BY 50. Rollback: DROP SEQUENCE engagement_outbox_seq;
CREATE SEQUENCE IF NOT EXISTS engagement_outbox_seq INCREMENT BY 50;
