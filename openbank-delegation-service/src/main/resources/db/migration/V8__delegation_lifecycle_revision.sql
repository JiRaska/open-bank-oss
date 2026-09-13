-- Monotonic delegation lifecycle ordering (P0).
--
-- The database owns the revision and state machine, not one application image. That is required
-- during a rolling deployment: an older pod still writes a detached entity with no revision field,
-- and must neither skip the counter nor overwrite a terminal state with a stale ACTIVE snapshot.
ALTER TABLE delegation_grants
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE delegation_grants
    ADD CONSTRAINT chk_delegation_lifecycle_revision_nonnegative
        CHECK (lifecycle_revision >= 0) NOT VALID;

-- ADD CONSTRAINT takes an ACCESS EXCLUSIVE lock; `NOT VALID` keeps that transaction
-- metadata-only. V9 validates in a separate Flyway transaction so this strong lock is released
-- before the table scan begins.

CREATE FUNCTION delegation_enforce_lifecycle_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
        IF NEW.lifecycle_revision IS DISTINCT FROM OLD.lifecycle_revision THEN
            RAISE EXCEPTION
                'delegation lifecycle revision may change only with status (% -> %, revision % -> %)',
                OLD.status, NEW.status, OLD.lifecycle_revision, NEW.lifecycle_revision;
        END IF;

        -- Hibernate merge from an old image still includes status in its UPDATE. If two stale
        -- snapshots target the same state, the second writer must not overwrite the first one's
        -- SCA or closure evidence while preserving its revision. Non-lifecycle fields are outside
        -- this guard and remain compatible with metadata-only edits during the rolling deploy.
        IF ROW(
            NEW.accept_sca_session_id,
            NEW.closed_at,
            NEW.closed_by,
            NEW.closed_reason
        ) IS DISTINCT FROM ROW(
            OLD.accept_sca_session_id,
            OLD.closed_at,
            OLD.closed_by,
            OLD.closed_reason
        ) THEN
            RAISE EXCEPTION 'delegation lifecycle evidence may change only with status for %', OLD.id;
        END IF;
        RETURN NEW;
    END IF;

    IF NOT (
        (OLD.status = 'OFFERED' AND NEW.status IN ('ACTIVE', 'DECLINED', 'REVOKED')) OR
        (OLD.status = 'ACTIVE' AND NEW.status IN ('SUSPENDED', 'REVOKED', 'RENOUNCED', 'EXPIRED')) OR
        (OLD.status = 'SUSPENDED' AND NEW.status IN ('ACTIVE', 'REVOKED', 'RENOUNCED'))
    ) THEN
        RAISE EXCEPTION 'illegal delegation lifecycle transition % -> % for %', OLD.status, NEW.status, OLD.id;
    END IF;

    -- New code supplies OLD+1 under a compare-and-set. An old rolling-deployment writer supplies
    -- OLD (because it does not map the column). Both are accepted, every other value is rejected,
    -- and the database writes the one authoritative answer.
    IF NEW.lifecycle_revision IS DISTINCT FROM OLD.lifecycle_revision
        AND NEW.lifecycle_revision IS DISTINCT FROM OLD.lifecycle_revision + 1 THEN
        RAISE EXCEPTION
            'illegal delegation lifecycle revision % -> % for %',
            OLD.lifecycle_revision, NEW.lifecycle_revision, OLD.id;
    END IF;
    NEW.lifecycle_revision := OLD.lifecycle_revision + 1;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_delegation_lifecycle_transition
    BEFORE UPDATE OF status, lifecycle_revision ON delegation_grants
    FOR EACH ROW
    EXECUTE FUNCTION delegation_enforce_lifecycle_transition();

-- Hibernate may flush the newly inserted outbox entity before it flushes the grant UPDATE. A
-- normal BEFORE/AFTER INSERT trigger would then read the previous revision and stamp a lie. The
-- constraint trigger is deferred to transaction commit, after both statements, and overwrites any
-- application-supplied value with the committed grant revision. This also upgrades events emitted
-- by an old producer image without requiring a flag-day deployment.
CREATE FUNCTION delegation_stamp_outbox_lifecycle_revision()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    committed_revision BIGINT;
BEGIN
    SELECT lifecycle_revision
      INTO STRICT committed_revision
      FROM delegation_grants
     WHERE id = NEW.aggregate_id;

    UPDATE delegation_outbox
       SET payload = jsonb_set(
           payload::jsonb,
           '{lifecycleRevision}',
           to_jsonb(committed_revision),
           TRUE
       )::text
     WHERE id = NEW.id;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_delegation_outbox_lifecycle_revision
    AFTER INSERT ON delegation_outbox
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.event_type IN (
        'DelegationOffered',
        'DelegationActivated',
        'DelegationDeclined',
        'DelegationRevoked',
        'DelegationSuspended',
        'DelegationReinstated',
        'DelegationRenounced',
        'DelegationExpired'
    ))
    EXECUTE FUNCTION delegation_stamp_outbox_lifecycle_revision();

-- Rollback (manual, expand/migrate/contract): keep this migration applied when rolling the new
-- producer image back; the triggers deliberately support the old writer and keep its events safe.
-- First roll back/reconcile every revision-aware consumer, drain all outbox rows, and prove no
-- revised event is in flight. Only then drop the two triggers/functions, the CHECK, and finally
-- lifecycle_revision. Dropping the column discards ordering state and is therefore never an
-- automatic rollback step.
