-- ADR-0380: extend the shared permit ledger to the two lending commitment points.
-- Rollback: disable lending writers and drop admit_passport_loan. Retain the expanded
-- operation constraint and consumed rows for audit while account writers continue.
ALTER TABLE passport_account_permits
    DROP CONSTRAINT passport_account_permits_operation_check,
    ADD CONSTRAINT passport_account_permits_operation_check
        CHECK (operation IN ('OPEN', 'ACTIVATE', 'SIGN_LOAN', 'DISBURSE_LOAN'));

ALTER FUNCTION admit_passport_account(UUID, TEXT, UUID, UUID, UUID, TEXT)
    RENAME TO admit_passport_fos_operation;

CREATE FUNCTION admit_passport_account(
    p_grant UUID, p_operation TEXT, p_operation_id UUID, p_party UUID, p_revision UUID, p_binding TEXT
) RETURNS TIMESTAMPTZ LANGUAGE sql AS $$
    SELECT CASE WHEN p_operation IN ('OPEN', 'ACTIVATE') THEN
        admit_passport_fos_operation(p_grant, p_operation, p_operation_id, p_party, p_revision, p_binding)
    END
$$;

CREATE FUNCTION admit_passport_loan(
    p_grant UUID, p_operation TEXT, p_operation_id UUID, p_party UUID, p_revision UUID,
    p_binding TEXT, p_country TEXT, p_bank UUID, p_establishment UUID,
    p_channel TEXT, p_segment TEXT, p_pack_digest TEXT
) RETURNS TIMESTAMPTZ LANGUAGE plpgsql AS $$
BEGIN
    IF p_operation IS NULL OR p_operation NOT IN ('SIGN_LOAN', 'DISBURSE_LOAN') THEN
        RETURN NULL;
    END IF;

    -- Check the trusted lending route and product type while holding the same grant
    -- lock as revocation. The shared admission then checks the grant's scope, live
    -- subject evidence, effective dates and exact permit binding.
    PERFORM 1 FROM passport_grants g
        JOIN catalog_revisions r ON r.offering_id = g.offering_id
        WHERE g.id = p_grant AND g.host_country::TEXT = p_country AND r.id = p_revision
            AND g.bank_id = p_bank AND g.establishment_id = p_establishment AND g.mode = 'FOS'
            AND g.channel = p_channel AND g.customer_segment = p_segment AND g.pack_set_digest = p_pack_digest
            AND r.schema_id = 'org.openbank.banking.loan'
        FOR UPDATE OF g;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    RETURN admit_passport_fos_operation(p_grant, p_operation, p_operation_id, p_party, p_revision, p_binding);
END;
$$;
