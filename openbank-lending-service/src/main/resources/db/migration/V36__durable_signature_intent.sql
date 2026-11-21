-- SPDX-License-Identifier: Apache-2.0
-- ADR-0380: persist the catalogue-backed signature request before applying its effect.
-- Rollback: disable signature writers and the recovery schedule, drain pending work, then
-- revert the application code. Keep the intent rows for evidence after first use.
CREATE TABLE lending_signature_intent (
    operation_id UUID PRIMARY KEY,
    application_id UUID NOT NULL UNIQUE REFERENCES loan_application(id) ON DELETE RESTRICT,
    revision_id UUID NOT NULL,
    revision_content_hash TEXT NOT NULL CHECK (revision_content_hash ~ '^[0-9a-f]{64}$'),
    artefact_hash VARCHAR(64) NOT NULL CHECK (btrim(artefact_hash) <> ''),
    actor TEXT NOT NULL CHECK (btrim(actor) <> ''),
    synthetic BOOLEAN NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + interval '10 seconds'),
    completed_at TIMESTAMPTZ
);

CREATE INDEX lending_signature_intent_due ON lending_signature_intent (next_attempt_at, application_id)
    WHERE completed_at IS NULL;
