-- SPDX-License-Identifier: Apache-2.0
-- Rollback: stop FOS admission, reconcile existing operations, then disable service writers.
-- Retain the route, permits and state guard while any FOS application remains open.
CREATE TABLE lending_fos_route (
    application_id UUID PRIMARY KEY REFERENCES loan_application(id) ON DELETE CASCADE,
    route JSONB NOT NULL
);

CREATE TABLE lending_fos_operation (
    operation_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES lending_fos_route(application_id) ON DELETE CASCADE,
    action TEXT NOT NULL CHECK (action IN ('SIGN', 'DISBURSE')),
    command JSONB NOT NULL,
    admitted_at TIMESTAMPTZ,
    refused_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + interval '30 seconds'),
    UNIQUE (application_id, action),
    CHECK (refused_at IS NULL OR admitted_at IS NULL),
    CHECK (completed_at IS NULL OR admitted_at IS NOT NULL)
);

CREATE INDEX lending_fos_operation_due ON lending_fos_operation(next_attempt_at, operation_id)
    WHERE completed_at IS NULL AND refused_at IS NULL;

CREATE FUNCTION lending_guard_fos_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    operation TEXT;
    target TEXT;
BEGIN
    IF NEW.status IS NOT DISTINCT FROM OLD.status OR NOT EXISTS (
        SELECT 1 FROM lending_fos_route WHERE application_id = OLD.id
    ) THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'AWAITING_SIGNATURE' THEN
        operation := 'SIGN';
        target := 'SIGNED';
    ELSIF OLD.status = 'READY_TO_DISBURSE' THEN
        operation := 'DISBURSE';
        target := 'DISBURSED';
    ELSE
        RETURN NEW;
    END IF;

    IF NEW.status::text = target THEN
        IF NOT EXISTS (
            SELECT 1 FROM lending_fos_operation
            WHERE application_id = OLD.id AND action = operation AND admitted_at IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'FOS catalogue admission required' USING ERRCODE = '23514';
        END IF;
    ELSIF EXISTS (
        SELECT 1 FROM lending_fos_operation
        WHERE application_id = OLD.id AND action = operation AND refused_at IS NULL AND completed_at IS NULL
    ) THEN
        RAISE EXCEPTION 'FOS commitment in progress' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER lending_guard_fos_transition BEFORE UPDATE OF status ON loan_application
    FOR EACH ROW EXECUTE FUNCTION lending_guard_fos_transition();
