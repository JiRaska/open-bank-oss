-- ADR-0334 S8: replay store for every pension-service POST (IdempotencyReplayFilter).
-- scope_hash = SHA-256 of (principal, X-Customer-Party-Id, method, path, Idempotency-Key): the
-- edge relays every customer under one principal, so the party is part of the scope. Only the
-- first 2xx response is kept; a failed attempt is not stored, so a corrected retry runs.
--
-- Rollback: DROP TABLE pension_idempotency_records; (no other object references it; replay
-- protection then falls back to each step's own idempotency).
CREATE TABLE pension_idempotency_records (
    scope_hash    CHAR(64)    PRIMARY KEY,
    status        INTEGER     NOT NULL CHECK (status BETWEEN 200 AND 299),
    response_body TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX pension_idempotency_records_created_idx ON pension_idempotency_records (created_at);

-- ADR-0334 S8: optimistic locking on the contract row. Every lifecycle write checks the version it
-- read; a concurrent change (termination vs payout, death vs strategy) loses with 409 instead of
-- silently overwriting the winner. Rollback: ALTER TABLE pension_contracts DROP COLUMN row_version;
ALTER TABLE pension_contracts ADD COLUMN row_version INTEGER NOT NULL DEFAULT 0;
