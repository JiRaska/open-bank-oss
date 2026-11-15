-- SPDX-License-Identifier: Apache-2.0
-- Rollback: disable the worker before reverting it. Retain acknowledged discovery and pending receipts.
CREATE TABLE legal_isds_poll_run (
    id UUID PRIMARY KEY,
    mailbox VARCHAR(123) NOT NULL,
    window_from TIMESTAMPTZ NOT NULL,
    window_through TIMESTAMPTZ NOT NULL CHECK (window_through > window_from),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    discovered_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    status VARCHAR(24) NOT NULL CHECK (status IN ('RUNNING','COMPLETE','INCOMPLETE','DISCOVERY_FAILED','INTERRUPTED')),
    discovered_count INTEGER NOT NULL DEFAULT 0 CHECK (discovered_count >= 0),
    imported_count INTEGER NOT NULL DEFAULT 0 CHECK (imported_count >= 0),
    pending_count BIGINT CHECK (pending_count >= 0)
);

CREATE INDEX legal_isds_poll_run_mailbox ON legal_isds_poll_run (mailbox, started_at DESC, id DESC);
CREATE INDEX legal_isds_poll_discovery ON legal_isds_poll_run (mailbox, window_through DESC)
    WHERE discovered_at IS NOT NULL;

CREATE TABLE legal_isds_pending (
    mailbox VARCHAR(123) NOT NULL,
    delivery_id VARCHAR(19) NOT NULL,
    first_seen TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    attempted_at TIMESTAMPTZ,
    PRIMARY KEY (mailbox, delivery_id)
);
