-- ADR-0380. Reuse the catalogue's commit-ordered cursor and immutable outbox rows.
-- Rollback: stop consuming passport grant snapshots, then drop the trigger and functions.
-- Retain published rows and cursor positions so existing consumers can resume.
CREATE FUNCTION write_passport_grant_snapshot(grant_row passport_grants) RETURNS void LANGUAGE sql AS $$
    INSERT INTO catalog_outbox (id, aggregate_type, aggregate_id, event_type, schema_version, occurred_at, payload)
    VALUES (
        gen_random_uuid(), 'PASSPORT_GRANT', grant_row.id, 'com.openbank.catalog.passport-grant.changed', 1,
        COALESCE(grant_row.revoked_at, grant_row.approved_at, clock_timestamp()),
        jsonb_build_object(
            'grantId', grant_row.id,
            'version', 1 + (grant_row.approved_at IS NOT NULL)::INT + (grant_row.revoked_at IS NOT NULL)::INT,
            'state', CASE WHEN grant_row.revoked_at IS NOT NULL THEN 'REVOKED'
                          WHEN grant_row.approved_at IS NOT NULL THEN 'APPROVED'
                          ELSE 'PROPOSED' END,
            'bankId', grant_row.bank_id,
            'establishmentId', grant_row.establishment_id,
            'hostCountry', grant_row.host_country,
            'mode', grant_row.mode,
            'offeringId', grant_row.offering_id,
            'channel', grant_row.channel,
            'customerSegment', grant_row.customer_segment,
            'packSetDigest', grant_row.pack_set_digest,
            'effectiveFrom', grant_row.effective_from,
            'effectiveUntil', grant_row.effective_until
        )
    );
$$;

CREATE FUNCTION publish_passport_grant_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' OR (NEW.approved_at, NEW.revoked_at) IS DISTINCT FROM (OLD.approved_at, OLD.revoked_at) THEN
        PERFORM write_passport_grant_snapshot(NEW);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_publish_passport_grant_change
    AFTER INSERT OR UPDATE OF approved_at, revoked_at ON passport_grants
    FOR EACH ROW EXECUTE FUNCTION publish_passport_grant_change();

-- Readers can bootstrap from the current version for grants already present before this migration.
SELECT write_passport_grant_snapshot(g) FROM passport_grants g ORDER BY id;
