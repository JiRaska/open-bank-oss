-- SPDX-License-Identifier: AGPL-3.0-only
-- Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
-- A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
-- See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
--
-- ADR-0244 D7: transactional outbox for the case HITL proposal path. Column set matches
-- PanacheOutboxEntity + claimed_at (the #1201 cross-pod claim, included from day one).
-- Rollback: DROP TABLE case_outbox; (proposals then fail to persist — disable case-open first)

CREATE TABLE case_outbox (
    id            BIGSERIAL PRIMARY KEY,
    event_id      UUID         NOT NULL UNIQUE,
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(128) NOT NULL,
    payload       TEXT         NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    attempt_count INTEGER      NOT NULL DEFAULT 0,
    sent_at       TIMESTAMPTZ,
    last_error    TEXT,
    claimed_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_case_outbox_status_created_at ON case_outbox (status, created_at ASC);
CREATE INDEX idx_case_outbox_aggregate_id ON case_outbox (aggregate_id);
