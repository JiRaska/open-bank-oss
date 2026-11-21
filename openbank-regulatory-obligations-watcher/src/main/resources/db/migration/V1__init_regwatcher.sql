-- SPDX-License-Identifier: AGPL-3.0-only
-- Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
--
-- ADR-0367 D6 / ADR-0387 S4: the regulatory-obligations-watcher's own state.
-- Rollback: DROP TABLE watch_findings; DROP TABLE source_baselines; DROP TABLE watch_runs;
-- (no dependents; on rollback the next run re-reads every source as a first baseline and files nothing
-- for content changes that happened in between — state the gap in the rollback note.)

-- Per-URL baseline. sha256/fetched_at describe the last FETCHED read ONLY: a DENIED or
-- NOT_RENDERABLE attempt updates last_outcome/last_attempt_at and never overwrites the hash.
CREATE TABLE source_baselines (
    url              VARCHAR(1024) PRIMARY KEY,
    sha256           VARCHAR(64),
    fetched_at       TIMESTAMPTZ,
    last_outcome     VARCHAR(16)  NOT NULL,
    last_attempt_at  TIMESTAMPTZ  NOT NULL,
    last_reason      VARCHAR(255)
);

-- One row per finding per run, with the charter's audit.capture fields.
CREATE TABLE watch_findings (
    id               UUID PRIMARY KEY,
    fingerprint      VARCHAR(64)   NOT NULL,
    kind             VARCHAR(32)   NOT NULL,
    verdict          VARCHAR(32)   NOT NULL,
    disposition      VARCHAR(40)   NOT NULL,
    issue_url        VARCHAR(512),
    obligation_id    TEXT          NOT NULL,
    source_url       VARCHAR(1024) NOT NULL,
    sha256           VARCHAR(64),
    source_quote     TEXT,
    summary          TEXT          NOT NULL,
    model_id         VARCHAR(128),
    prompt_hash      VARCHAR(64),
    tool_calls       VARCHAR(255)  NOT NULL,
    policy_decision  VARCHAR(255)  NOT NULL,
    recorded_at      TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_watch_findings_fingerprint ON watch_findings (fingerprint);

-- Run starts, for the charter's runs_per_day limit (counted in the DB so a pod restart cannot reset it).
CREATE TABLE watch_runs (
    id          UUID PRIMARY KEY,
    started_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_watch_runs_started_at ON watch_runs (started_at);
