-- Freeze the independently captured expected pin and retain the worker copy's actual identity.
-- Existing rows remain NULL: metadata must never be guessed or backfilled from worker time.
ALTER TABLE billing_product_shadow_input ADD COLUMN expected_revision_identity TEXT;
ALTER TABLE billing_product_revision_ir ADD COLUMN actual_revision_identity TEXT;
-- Rollback: deploy the preceding application first; preserve these additive nullable columns
-- (and their evidence). Dropping them discards identity provenance and requires an explicit decision.

-- Enrich a legacy copy only with independently verified metadata. Never replace its original
-- identity/hash/IR/signature/timestamp, overwrite a recorded identity, or permit deletion.
CREATE OR REPLACE FUNCTION forbid_billing_revision_ir_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.actual_revision_identity IS NULL
       AND NEW.actual_revision_identity IS NOT NULL
       AND (to_jsonb(NEW) - 'actual_revision_identity') = (to_jsonb(OLD) - 'actual_revision_identity')
       AND (NEW.actual_revision_identity::jsonb ->> 'revisionId')::uuid = OLD.revision_id
       AND (NEW.actual_revision_identity::jsonb ->> 'revisionHash') = OLD.revision_hash
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'billing product revision IR copies are immutable';
END;
$$ LANGUAGE plpgsql;
