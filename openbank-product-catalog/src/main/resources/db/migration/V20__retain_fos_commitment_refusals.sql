-- SPDX-License-Identifier: Apache-2.0
-- Retain the terminal decision for an exact command under the grant lock.
-- Rollback: stop commitment writers and retain refusal rows for reconciliation.
CREATE TABLE passport_commitment_refusals (
    operation TEXT NOT NULL CHECK (operation IN ('SIGN', 'DISBURSE')),
    operation_id UUID NOT NULL,
    grant_id UUID NOT NULL REFERENCES passport_grants(id),
    party_id UUID NOT NULL,
    product_revision_id UUID NOT NULL REFERENCES catalog_revisions(id),
    binding_digest TEXT NOT NULL CHECK (binding_digest ~ '^[0-9a-f]{64}$'),
    refused_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (operation, operation_id, grant_id, party_id, product_revision_id, binding_digest)
);

CREATE OR REPLACE FUNCTION admit_passport_commitment(
    p_grant UUID, p_operation TEXT, p_operation_id UUID, p_party UUID, p_revision UUID,
    p_binding TEXT, p_country TEXT, p_bank UUID, p_establishment UUID,
    p_channel TEXT, p_segment TEXT, p_pack_digest TEXT, p_revision_content_hash TEXT, p_schema_id TEXT
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
            AND g.bank_id = p_bank AND g.establishment_id = p_establishment AND g.mode = 'FOS'
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

    receipt := admit_passport_fos_operation(p_grant, p_operation, p_operation_id, p_party, p_revision, p_binding);
    IF receipt IS NULL THEN
        INSERT INTO passport_commitment_refusals (
            operation, operation_id, grant_id, party_id, product_revision_id, binding_digest
        ) VALUES (p_operation, p_operation_id, p_grant, p_party, p_revision, p_binding)
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN receipt;
END;
$$;
