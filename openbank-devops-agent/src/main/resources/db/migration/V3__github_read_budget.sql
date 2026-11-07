-- SPDX-License-Identifier: AGPL-3.0-only
-- Rollback: roll back the application, retaining these additive budget tables.
-- Dropping github_read_requests and github_read_budget is a separate operator decision.
CREATE TABLE github_read_budget (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    reservation_serial BIGINT NOT NULL DEFAULT 0,
    blocked_until TIMESTAMPTZ
);
INSERT INTO github_read_budget (id) VALUES (1);
CREATE TABLE github_read_requests (
    id UUID PRIMARY KEY,
    requested_at TIMESTAMPTZ NOT NULL,
    observer BOOLEAN NOT NULL
);
CREATE INDEX idx_github_read_requested_at ON github_read_requests (requested_at);
