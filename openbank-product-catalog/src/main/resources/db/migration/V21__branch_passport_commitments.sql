-- SPDX-License-Identifier: Apache-2.0
-- ADR-0380 C3: use the same permit and revocation fence for both lending modes.
-- Rollback: roll catalogue code back first. Preserve grants, terminal refusals and used permits.
-- The original function signature remains available and can only admit FOS.
CREATE FUNCTION admit_passport_scoped_operation(
    p_grant UUID, p_operation TEXT, p_operation_id UUID, p_party UUID, p_revision UUID, p_binding TEXT, p_mode TEXT
) RETURNS TIMESTAMPTZ LANGUAGE plpgsql AS $$
DECLARE
    grant_row passport_grants%ROWTYPE;
    permit UUID;
BEGIN
    -- OPEN and ACTIVATE still require the FOS account path until B2.
    IF p_mode = 'BRANCH' AND (p_operation IS NULL OR p_operation NOT IN ('SIGN', 'DISBURSE')) THEN
        RETURN NULL;
    END IF;
    SELECT * INTO grant_row FROM passport_grants WHERE id = p_grant FOR UPDATE;
    IF NOT FOUND OR grant_row.mode IS DISTINCT FROM p_mode THEN
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

CREATE FUNCTION admit_passport_commitment(
    p_grant UUID, p_operation TEXT, p_operation_id UUID, p_party UUID, p_revision UUID,
    p_binding TEXT, p_country TEXT, p_bank UUID, p_establishment UUID,
    p_channel TEXT, p_segment TEXT, p_pack_digest TEXT, p_revision_content_hash TEXT, p_schema_id TEXT, p_mode TEXT
) RETURNS TIMESTAMPTZ LANGUAGE plpgsql AS $$
DECLARE
    receipt TIMESTAMPTZ;
BEGIN
    IF p_operation IS NULL OR p_operation NOT IN ('SIGN', 'DISBURSE') THEN
        RETURN NULL;
    END IF;

    -- Check the trusted lending route and product type while holding the same grant
    -- lock as revocation. The shared admission then checks the grant's scope, live
    -- subject evidence, effective dates and exact permit binding.
    PERFORM 1 FROM passport_grants g
        JOIN catalog_revisions r ON r.offering_id = g.offering_id
        WHERE g.id = p_grant AND g.host_country::TEXT = p_country AND r.id = p_revision
            AND g.bank_id = p_bank AND g.establishment_id = p_establishment AND g.mode = p_mode
            AND g.channel = p_channel AND g.customer_segment = p_segment AND g.pack_set_digest = p_pack_digest
            AND r.schema_id = p_schema_id AND r.content_hash::TEXT = p_revision_content_hash
        FOR UPDATE OF g;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF EXISTS (
        SELECT 1 FROM passport_commitment_refusals f
        WHERE f.operation = p_operation AND f.operation_id = p_operation_id
            AND f.grant_id = p_grant AND f.party_id = p_party
            AND f.product_revision_id = p_revision AND f.binding_digest = p_binding
    ) THEN
        RETURN NULL;
    END IF;

    receipt := admit_passport_scoped_operation(p_grant, p_operation, p_operation_id, p_party, p_revision, p_binding, p_mode);
    IF receipt IS NULL THEN
        INSERT INTO passport_commitment_refusals (
            operation, operation_id, grant_id, party_id, product_revision_id, binding_digest
        ) VALUES (p_operation, p_operation_id, p_grant, p_party, p_revision, p_binding)
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN receipt;
END;
$$;

CREATE OR REPLACE FUNCTION admit_passport_commitment(
    p_grant UUID, p_operation TEXT, p_operation_id UUID, p_party UUID, p_revision UUID,
    p_binding TEXT, p_country TEXT, p_bank UUID, p_establishment UUID,
    p_channel TEXT, p_segment TEXT, p_pack_digest TEXT, p_revision_content_hash TEXT, p_schema_id TEXT
) RETURNS TIMESTAMPTZ LANGUAGE sql AS $$
    SELECT admit_passport_commitment(p_grant, p_operation, p_operation_id, p_party, p_revision,
        p_binding, p_country, p_bank, p_establishment, p_channel, p_segment, p_pack_digest,
        p_revision_content_hash, p_schema_id, 'FOS')
$$;

CREATE OR REPLACE FUNCTION admit_passport_fos_operation(
    p_grant UUID, p_operation TEXT, p_operation_id UUID, p_party UUID, p_revision UUID, p_binding TEXT
) RETURNS TIMESTAMPTZ LANGUAGE sql AS $$
    SELECT admit_passport_scoped_operation(p_grant, p_operation, p_operation_id, p_party, p_revision, p_binding, 'FOS')
$$;
