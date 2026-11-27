-- SPDX-License-Identifier: Apache-2.0
-- Positive state and audit share one append-only row. Revoke by appending a tombstone.
-- Rollback: disable reads and writes, retain this record for audit, then roll back the binary.
CREATE TABLE mcp_native_passkey_age_exception_audit (
    sequence_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    party_id uuid NOT NULL,
    actor_subject text NOT NULL CHECK (length(actor_subject) BETWEEN 1 AND 255),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    minimum_age_minutes integer CHECK (minimum_age_minutes BETWEEN 1 AND 4319),
    expires_at timestamptz,
    reason text CHECK (length(reason) BETWEEN 1 AND 240),
    CHECK ((minimum_age_minutes IS NULL AND expires_at IS NULL AND reason IS NULL)
        OR (minimum_age_minutes IS NOT NULL AND expires_at IS NOT NULL AND reason IS NOT NULL))
);

CREATE INDEX mcp_native_age_exception_latest
    ON mcp_native_passkey_age_exception_audit (party_id, sequence_id DESC);

-- An eligibility transition permanently invalidates the current policy. Returning to ACTIVE /
-- SYNTHETIC requires a new human decision and cannot revive an old exception.
CREATE FUNCTION invalidate_mcp_native_age_exception() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.party_type, OLD.classification, OLD.status)
        IS DISTINCT FROM (NEW.party_type, NEW.classification, NEW.status)
        AND OLD.party_type = 'INDIVIDUAL' AND OLD.classification = 'SYNTHETIC' AND OLD.status = 'ACTIVE'
        AND EXISTS (
            SELECT 1 FROM mcp_native_passkey_age_exception_audit
            WHERE party_id = OLD.party_id
        )
    THEN
        INSERT INTO mcp_native_passkey_age_exception_audit (party_id, actor_subject)
        VALUES (OLD.party_id, 'system:party-eligibility');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER invalidate_mcp_native_age_exception_on_party_change
    AFTER UPDATE OF party_type, classification, status ON parties
    FOR EACH ROW EXECUTE FUNCTION invalidate_mcp_native_age_exception();
