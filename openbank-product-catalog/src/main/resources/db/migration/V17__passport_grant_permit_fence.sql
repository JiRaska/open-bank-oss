-- ADR-0380: catalogue owns ordering between consumption and revocation.
-- Rollback before use: drop admit_passport_account before issue, consume, approve and revoke,
-- then drop passport_grant_subject_evidence before the permit and grant tables.
-- After use, revert writers first and retain rows and functions for reconciliation and audit.

CREATE TABLE passport_grants (
    id UUID PRIMARY KEY,
    bank_id UUID NOT NULL,
    establishment_id UUID NOT NULL,
    host_country CHAR(2) NOT NULL CHECK (host_country ~ '^[A-Z]{2}$' AND host_country <> 'CZ'),
    mode TEXT NOT NULL CHECK (mode IN ('FOS', 'BRANCH')),
    offering_id UUID NOT NULL REFERENCES catalog_offerings(id),
    channel TEXT NOT NULL CHECK (btrim(channel) <> ''),
    customer_segment TEXT NOT NULL CHECK (btrim(customer_segment) <> ''),
    pack_set_digest TEXT NOT NULL CHECK (pack_set_digest ~ '^[a-f0-9]{64}$'),
    evidence_ref TEXT NOT NULL CHECK (btrim(evidence_ref) <> ''),
    proposed_by UUID NOT NULL,
    approved_by UUID CHECK (approved_by <> proposed_by),
    approved_at TIMESTAMPTZ,
    approval_reason TEXT,
    CHECK (
        (approved_by IS NULL AND approved_at IS NULL AND approval_reason IS NULL)
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND nullif(btrim(approval_reason), '') IS NOT NULL)
    ),
    effective_from TIMESTAMPTZ NOT NULL,
    effective_until TIMESTAMPTZ NOT NULL CHECK (effective_until > effective_from),
    revoked_at TIMESTAMPTZ,
    revoked_by UUID,
    revoke_reason TEXT,
    CHECK (
        (revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND nullif(btrim(revoke_reason), '') IS NOT NULL)
    )
);

CREATE TABLE passport_account_permits (
    id UUID PRIMARY KEY,
    grant_id UUID NOT NULL REFERENCES passport_grants(id),
    operation TEXT NOT NULL CHECK (operation IN ('OPEN', 'ACTIVATE')),
    operation_id UUID NOT NULL,
    party_id UUID NOT NULL,
    product_revision_id UUID NOT NULL REFERENCES catalog_revisions(id),
    binding_digest TEXT NOT NULL CHECK (binding_digest ~ '^[a-f0-9]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ CHECK (consumed_at < expires_at),
    UNIQUE (operation, operation_id)
);

-- Both functions lock the grant first. Revocation updates the same row. The grant can
-- only be revoked once. Renewal is a new grant ID, so an old permit cannot become valid again.
CREATE FUNCTION issue_passport_account_permit(
    p_permit UUID, p_grant UUID, p_operation TEXT, p_operation_id UUID,
    p_party UUID, p_revision UUID, p_binding TEXT,
    p_bank UUID, p_establishment UUID, p_country TEXT, p_mode TEXT,
    p_channel TEXT, p_segment TEXT, p_pack_digest TEXT
) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE
    grant_row passport_grants%ROWTYPE;
    permit_row passport_account_permits%ROWTYPE;
    at_time TIMESTAMPTZ;
BEGIN
    SELECT * INTO grant_row FROM passport_grants WHERE id = p_grant FOR UPDATE;
    IF NOT FOUND OR (
        grant_row.bank_id, grant_row.establishment_id, grant_row.host_country::TEXT, grant_row.mode,
        grant_row.channel, grant_row.customer_segment, grant_row.pack_set_digest
    ) IS DISTINCT FROM (p_bank, p_establishment, p_country, p_mode, p_channel, p_segment, p_pack_digest)
    THEN
        RETURN NULL;
    END IF;
    SELECT * INTO permit_row FROM passport_account_permits
        WHERE operation = p_operation AND operation_id = p_operation_id;
    IF FOUND THEN
        IF (permit_row.grant_id, permit_row.party_id, permit_row.product_revision_id, permit_row.binding_digest)
            IS DISTINCT FROM (p_grant, p_party, p_revision, p_binding)
        THEN
            RETURN NULL;
        END IF;
        -- The exact original request always resolves to the same permit, including after
        -- revocation. Consumption decides whether the receipt is recoverable or refused.
        RETURN permit_row.id;
    END IF;

    at_time := clock_timestamp();
    IF grant_row.approved_by IS NULL OR grant_row.revoked_at IS NOT NULL OR at_time < grant_row.effective_from
        OR at_time >= grant_row.effective_until OR NOT EXISTS (
            SELECT 1 FROM catalog_revisions
            WHERE id = p_revision AND offering_id = grant_row.offering_id
                AND state = 'PUBLISHED' AND effective_from <= at_time
                AND (effective_to IS NULL OR effective_to > at_time)
        )
    THEN
        RETURN NULL;
    END IF;

    INSERT INTO passport_account_permits (
        id, grant_id, operation, operation_id, party_id, product_revision_id, binding_digest, expires_at
    ) VALUES (
        p_permit, p_grant, p_operation, p_operation_id, p_party, p_revision, p_binding,
        least(grant_row.effective_until, at_time + interval '30 seconds')
    ) ON CONFLICT (operation, operation_id) DO NOTHING;
    SELECT * INTO permit_row FROM passport_account_permits
        WHERE operation = p_operation AND operation_id = p_operation_id;
    IF (permit_row.grant_id, permit_row.party_id, permit_row.product_revision_id, permit_row.binding_digest)
        IS DISTINCT FROM (p_grant, p_party, p_revision, p_binding)
    THEN
        RETURN NULL;
    END IF;
    RETURN permit_row.id;
END;
$$;

CREATE FUNCTION consume_passport_account_permit(
    p_permit UUID, p_operation TEXT, p_operation_id UUID, p_party UUID, p_revision UUID, p_binding TEXT
) RETURNS TIMESTAMPTZ LANGUAGE plpgsql AS $$
DECLARE
    grant_row passport_grants%ROWTYPE;
    permit_row passport_account_permits%ROWTYPE;
    at_time TIMESTAMPTZ;
BEGIN
    SELECT g.* INTO grant_row FROM passport_grants g
        JOIN passport_account_permits p ON p.grant_id = g.id WHERE p.id = p_permit FOR UPDATE OF g;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT * INTO STRICT permit_row FROM passport_account_permits WHERE id = p_permit;
    IF (permit_row.operation, permit_row.operation_id, permit_row.party_id,
        permit_row.product_revision_id, permit_row.binding_digest)
        IS DISTINCT FROM (p_operation, p_operation_id, p_party, p_revision, p_binding)
    THEN
        RETURN NULL;
    END IF;
    IF permit_row.consumed_at IS NOT NULL THEN
        RETURN permit_row.consumed_at;
    END IF;
    at_time := clock_timestamp();
    IF grant_row.approved_by IS NULL OR grant_row.revoked_at IS NOT NULL OR at_time < grant_row.effective_from
        OR at_time >= permit_row.expires_at OR NOT EXISTS (
            SELECT 1 FROM catalog_revisions
            WHERE id = permit_row.product_revision_id AND offering_id = grant_row.offering_id
                AND state = 'PUBLISHED' AND effective_from <= at_time
                AND (effective_to IS NULL OR effective_to > at_time)
        )
    THEN
        RETURN NULL;
    END IF;
    UPDATE passport_account_permits SET consumed_at = at_time WHERE id = p_permit;
    RETURN at_time;
END;
$$;

CREATE FUNCTION approve_passport_grant(p_grant UUID, p_actor UUID, p_reason TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    UPDATE passport_grants SET approved_by = p_actor, approved_at = clock_timestamp(), approval_reason = p_reason
        WHERE id = p_grant AND proposed_by <> p_actor AND approved_by IS NULL AND revoked_at IS NULL
            AND effective_until > clock_timestamp();
    RETURN FOUND;
END;
$$;

CREATE FUNCTION revoke_passport_grant(p_grant UUID, p_actor UUID, p_reason TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM passport_grants WHERE id = p_grant AND revoked_at IS NULL FOR UPDATE;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;
    UPDATE passport_grants SET revoked_at = clock_timestamp(), revoked_by = p_actor, revoke_reason = p_reason
        WHERE id = p_grant;
    RETURN FOUND;
END;
$$;

-- Only the verified-facts producer may write this binding. The account caller supplies neither
-- country nor identity evidence. Replays use the immutable receipt even if evidence later expires.
CREATE TABLE passport_grant_subject_evidence (
    grant_id UUID NOT NULL REFERENCES passport_grants(id),
    party_id UUID NOT NULL,
    evidence_ref TEXT NOT NULL CHECK (btrim(evidence_ref) <> ''),
    valid_until TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (grant_id, party_id)
);

CREATE FUNCTION admit_passport_account(
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
    -- The initial account implementation books in the home establishment. Branch booking
    -- must go through the separately approved branch ledger path.
    IF grant_row.mode <> 'FOS' THEN
        RETURN NULL;
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
