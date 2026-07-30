-- SPDX-License-Identifier: Apache-2.0
-- Campaign first slice (ADR-0200): definitions, enrolments, send decisions, segment artifacts.

CREATE TABLE campaigns (
    id              UUID PRIMARY KEY,
    name            TEXT        NOT NULL,
    goal            TEXT        NOT NULL,
    segment_name    TEXT        NOT NULL,
    segment_version INT         NOT NULL,
    steps_json      JSONB       NOT NULL,
    state           TEXT        NOT NULL,
    created_by      TEXT        NOT NULL,
    approved_by     TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE enrolments (
    id           UUID PRIMARY KEY,
    campaign_id  UUID        NOT NULL REFERENCES campaigns (id),
    party_id     UUID        NOT NULL,
    state        TEXT        NOT NULL,
    current_step INT         NOT NULL DEFAULT 0,
    started_at   TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (campaign_id, party_id)
);
CREATE INDEX idx_enrolments_party ON enrolments (party_id);

-- ADR-0219 D2: the send log is the frequency-cap counter's durable source — the cap is
-- reconstructable by replaying this table, never dependent on cache survival.
CREATE TABLE send_log (
    id          UUID PRIMARY KEY,
    campaign_id UUID        NOT NULL REFERENCES campaigns (id),
    party_id    UUID        NOT NULL,
    step_order  INT         NOT NULL,
    outcome     TEXT        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_send_log_party_time ON send_log (party_id, occurred_at);

-- ADR-0201 D1: segments are versioned artifacts; versions are immutable once created.
CREATE TABLE segments (
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    version    INT         NOT NULL,
    rules_json JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (name, version)
);
