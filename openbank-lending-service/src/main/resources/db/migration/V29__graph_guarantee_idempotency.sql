-- SPDX-License-Identifier: Apache-2.0
-- A completed receipt commits with its guarantee fact or decision and approval outbox row.
-- Rollback before writer adoption: DROP TABLE lending_graph_guarantee_idempotency;
-- After adoption, retain receipts for at least the lifetime of their guarantee facts;
-- deleting them permits an old key to create a second fact and is not a safe rollback.
CREATE TABLE lending_graph_guarantee_idempotency (
    receipt_id UUID PRIMARY KEY,
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('PROPOSE', 'DECIDE')),
    idempotency_key VARCHAR(128) NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    fingerprint CHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    guarantee_id UUID NOT NULL REFERENCES lending_graph_guarantee(guarantee_id),
    revision BIGINT NOT NULL CHECK (revision > 0),
    response_status VARCHAR(16) NOT NULL CHECK (response_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT lending_graph_guarantee_idempotency_key UNIQUE (operation, idempotency_key)
);
GRANT SELECT, INSERT ON lending_graph_guarantee_idempotency TO openbank;
