-- SPDX-License-Identifier: Apache-2.0
-- Each proposed payload is immutable. Approval assigns that exact payload to a future reference day.
-- Rollback: revert the reader and authoring routes first. Retain the revision and receipt tables as decision evidence.
CREATE TABLE cz_credit_policy_revision (
    id UUID PRIMARY KEY,
    revision INTEGER NOT NULL CHECK (revision >= 3),
    effective_from DATE NOT NULL,
    payload TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PROPOSED', 'EXECUTED', 'REJECTED')),
    proposed_by TEXT NOT NULL,
    proposed_at TIMESTAMPTZ NOT NULL,
    decided_by TEXT,
    decided_at TIMESTAMPTZ,
    decision_reason TEXT,
    CHECK (
        (state = 'PROPOSED' AND decided_by IS NULL AND decided_at IS NULL AND decision_reason IS NULL)
        OR (state IN ('EXECUTED', 'REJECTED') AND decided_by IS NOT NULL
            AND decided_at IS NOT NULL AND decided_by <> proposed_by)
    )
);

CREATE UNIQUE INDEX uq_cz_policy_active_revision ON cz_credit_policy_revision (revision)
    WHERE state = 'EXECUTED';
CREATE UNIQUE INDEX uq_cz_policy_active_day ON cz_credit_policy_revision (effective_from)
    WHERE state = 'EXECUTED';
CREATE UNIQUE INDEX uq_cz_policy_live_content ON cz_credit_policy_revision (content_hash)
    WHERE state IN ('PROPOSED', 'EXECUTED');

CREATE TABLE cz_credit_policy_command_receipt (
    scoped_key_digest VARCHAR(64) PRIMARY KEY CHECK (scoped_key_digest ~ '^[0-9a-f]{64}$'),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    proposal_id UUID NOT NULL REFERENCES cz_credit_policy_revision(id) DEFERRABLE INITIALLY DEFERRED,
    response_state VARCHAR(16) NOT NULL CHECK (response_state IN ('PROPOSED', 'EXECUTED', 'REJECTED'))
);

-- A command receipt may reconstruct the first response from the immutable fields and terminal state.
CREATE FUNCTION protect_cz_credit_policy_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state = 'PROPOSED' THEN
            RETURN NEW;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.state = 'PROPOSED' AND NEW.state IN ('EXECUTED', 'REJECTED')
            AND (NEW.id, NEW.revision, NEW.effective_from, NEW.payload, NEW.content_hash, NEW.proposed_by, NEW.proposed_at)
                IS NOT DISTINCT FROM
                (OLD.id, OLD.revision, OLD.effective_from, OLD.payload, OLD.content_hash, OLD.proposed_by, OLD.proposed_at)
        THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'Credit policy content and terminal evidence are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER cz_credit_policy_revision_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON cz_credit_policy_revision
    FOR EACH ROW EXECUTE FUNCTION protect_cz_credit_policy_revision();

CREATE FUNCTION cz_credit_policy_prague_day() RETURNS date LANGUAGE sql VOLATILE AS $$
    SELECT (clock_timestamp() AT TIME ZONE 'Europe/Prague')::date
$$;

CREATE FUNCTION check_cz_credit_policy_future_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.effective_from <= cz_credit_policy_prague_day() THEN
        RAISE EXCEPTION 'Credit policy approval must take effect on a future Prague day'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_cz_policy_future_approval';
    END IF;
    RETURN NULL;
END;
$$;

-- Evaluate at commit, after the row and uniqueness locks. Waiting on either cannot backdate an approval.
CREATE CONSTRAINT TRIGGER ck_cz_policy_future_approval
    AFTER UPDATE ON cz_credit_policy_revision DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.state = 'EXECUTED') EXECUTE FUNCTION check_cz_credit_policy_future_approval();
