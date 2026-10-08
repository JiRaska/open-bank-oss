-- Preserve the source rows needed to reconcile historical audit delivery. V6 protects
-- their identity, payload and creation time from UPDATE; removal also changes that set.
-- No agent-service retention or purge path currently deletes this outbox. A future
-- retention policy must prove destination reconciliation and legal-hold handling first.
-- Rollback: disable replay, retain/export source and checkpoint evidence, then drop
-- these triggers and this function only under a reviewed retention decision. Never
-- delete source rows as a rollback. Table owners can still disable triggers or DROP
-- the table, so this is an application-role guard rather than tamper-proof storage.
CREATE FUNCTION agent_audit_outbox_reject_removal() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent audit outbox source rows cannot be removed';
END;
$$;

CREATE TRIGGER agent_audit_outbox_no_delete
    BEFORE DELETE ON agent_audit_outbox
    FOR EACH ROW EXECUTE FUNCTION agent_audit_outbox_reject_removal();

CREATE TRIGGER agent_audit_outbox_no_truncate
    BEFORE TRUNCATE ON agent_audit_outbox
    FOR EACH STATEMENT EXECUTE FUNCTION agent_audit_outbox_reject_removal();
