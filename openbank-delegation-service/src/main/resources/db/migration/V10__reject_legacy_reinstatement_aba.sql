-- Fail closed for a legacy producer that cannot prove which SUSPENDED state it read.
--
-- V8 deliberately accepts lifecycle writes from the rolling old image, whose entity does not map
-- lifecycle_revision. ACTIVE <-> SUSPENDED is cyclic, though: an old pod can read SUSPENDED at r2,
-- another writer can reinstate at r3 and suspend for a newer fraud signal at r4, and the stale old
-- merge would otherwise see SUSPENDED again and reopen authority at r5 (the ABA race).
--
-- Keep V8 immutable because it may already be applied. This replacement preserves its state graph,
-- legacy compatibility and authoritative revision assignment, but refuses the one legacy transition
-- that restores authority after a state can cycle. A new CAS writer supplies OLD+1 and is accepted.
CREATE OR REPLACE FUNCTION delegation_enforce_lifecycle_transition()
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

    IF OLD.status = 'SUSPENDED'
        AND NEW.status = 'ACTIVE'
        AND NEW.lifecycle_revision IS NOT DISTINCT FROM OLD.lifecycle_revision THEN
        RAISE EXCEPTION
            'legacy writer may not reinstate delegation % without a lifecycle revision', OLD.id;
    END IF;

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

-- Rollback: retain this function while any legacy producer can write. Replacing it with V8's body
-- reopens the ABA authority-restoration race. Contract only in the quiesced maintenance sequence in
-- the delegation runbook: freeze lifecycle writes and expiration, drain outbox and consumer lag,
-- stop producer pods, prove both facts again, then remove the complete V8-V10 lifecycle contract.
