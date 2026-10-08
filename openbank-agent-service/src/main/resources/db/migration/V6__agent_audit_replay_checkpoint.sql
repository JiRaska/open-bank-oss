-- Opt-in replay cursor; source outbox rows and their published_at state remain untouched.
-- Rollback: disable replay, preserve this table for evidence until campaigns are reconciled.
CREATE TABLE agent_audit_replay_checkpoint (
    campaign_id UUID PRIMARY KEY,
    from_at TIMESTAMPTZ NOT NULL,
    until_at TIMESTAMPTZ NOT NULL,
    max_events INTEGER NOT NULL CHECK (max_events BETWEEN 1 AND 5000),
    expected_count INTEGER NOT NULL CHECK (expected_count BETWEEN 1 AND max_events),
    source_manifest_sha256 CHAR(64) NOT NULL,
    cursor_created_at TIMESTAMPTZ,
    cursor_event_id UUID,
    cursor_payload_sha256 CHAR(64),
    acknowledged_count INTEGER NOT NULL DEFAULT 0,
    -- Broker ACKs only. Destination reconciliation is a separate required gate.
    destination_reconciled_at TIMESTAMPTZ,
    last_batch_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT agent_audit_replay_window CHECK (from_at < until_at),
    CONSTRAINT agent_audit_replay_destination_only_after_source
        CHECK (destination_reconciled_at IS NULL OR acknowledged_count = expected_count),
    CONSTRAINT agent_audit_replay_cursor_complete CHECK (
        (cursor_created_at IS NULL AND cursor_event_id IS NULL AND cursor_payload_sha256 IS NULL)
        OR (cursor_created_at IS NOT NULL AND cursor_event_id IS NOT NULL AND cursor_payload_sha256 IS NOT NULL)
    )
);

CREATE INDEX idx_agent_audit_outbox_replay
    ON agent_audit_outbox (created_at, event_id)
    WHERE published_at IS NOT NULL;

-- Replay must reuse the exact source envelope. Delivery-state updates remain permitted.
-- Rollback: DROP TRIGGER agent_audit_outbox_source_immutable ON agent_audit_outbox;
--           DROP FUNCTION agent_audit_outbox_reject_source_update();
CREATE FUNCTION agent_audit_outbox_reject_source_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.event_id IS DISTINCT FROM OLD.event_id
       OR NEW.payload IS DISTINCT FROM OLD.payload
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'agent audit outbox source fields are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER agent_audit_outbox_source_immutable
    BEFORE UPDATE ON agent_audit_outbox
    FOR EACH ROW EXECUTE FUNCTION agent_audit_outbox_reject_source_update();
