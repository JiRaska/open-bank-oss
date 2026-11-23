-- B2: context is read only from an exact consumed receipt, including after revocation.
-- Rollback: stop branch admissions; keep consumed receipts and scope immutability for audit.
CREATE OR REPLACE FUNCTION admit_passport_account(
    p_grant UUID, p_operation TEXT, p_operation_id UUID, p_party UUID, p_revision UUID, p_binding TEXT
) RETURNS TIMESTAMPTZ LANGUAGE plpgsql AS $$
DECLARE
    grant_row passport_grants%ROWTYPE;
    permit UUID;
BEGIN
    SELECT * INTO grant_row FROM passport_grants WHERE id = p_grant FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT id INTO permit FROM passport_account_permits
        WHERE operation = p_operation AND operation_id = p_operation_id AND grant_id = p_grant
            AND consumed_at IS NOT NULL;
    IF FOUND THEN
        RETURN consume_passport_account_permit(permit, p_operation, p_operation_id, p_party, p_revision, p_binding);
    END IF;
    PERFORM 1 FROM passport_grant_subject_evidence WHERE grant_id = p_grant AND party_id = p_party
        AND valid_until > clock_timestamp() FOR SHARE;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    PERFORM 1 FROM catalog_offerings WHERE id = grant_row.offering_id
        AND market -> 'countries' ? grant_row.host_country::TEXT;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    permit := issue_passport_account_permit(
        p_operation_id, p_grant, p_operation, p_operation_id, p_party, p_revision, p_binding,
        grant_row.bank_id, grant_row.establishment_id, grant_row.host_country::TEXT, grant_row.mode,
        grant_row.channel, grant_row.customer_segment, grant_row.pack_set_digest
    );
    RETURN consume_passport_account_permit(permit, p_operation, p_operation_id, p_party, p_revision, p_binding);
END;
$$;

CREATE FUNCTION preserve_passport_booking_scope() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.bank_id,NEW.establishment_id,NEW.host_country,NEW.mode,NEW.offering_id,
        NEW.channel,NEW.customer_segment,NEW.pack_set_digest)
       IS DISTINCT FROM (OLD.bank_id,OLD.establishment_id,OLD.host_country,OLD.mode,OLD.offering_id,
        OLD.channel,OLD.customer_segment,OLD.pack_set_digest) THEN
        RAISE EXCEPTION 'passport booking scope is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_preserve_passport_booking_scope BEFORE UPDATE ON passport_grants
    FOR EACH ROW EXECUTE FUNCTION preserve_passport_booking_scope();
