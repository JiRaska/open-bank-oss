-- SPDX-License-Identifier: Apache-2.0
-- Additive. Roll back callers first and retain committed receipts as historical evidence.

CREATE TABLE catalog_booking_grant_receipts (
    operation_id uuid PRIMARY KEY,
    subject_id uuid NOT NULL,
    product_revision_id uuid NOT NULL REFERENCES catalog_revisions(id),
    grant_revision_id uuid NOT NULL REFERENCES catalog_revisions(id),
    approval_digest char(64) NOT NULL,
    booked_context jsonb NOT NULL,
    consumed_at timestamptz NOT NULL
);

-- Called by the trusted account-opening adapter with the operation's durable identity.
-- Lock the grant row that publication/supersession changes before evaluating its interval.
-- A replay of a committed operation keeps its original receipt even after expiry.
CREATE FUNCTION catalog_consume_booking_grant(
    requested_operation uuid,
    requested_subject uuid,
    requested_product uuid,
    requested_grant uuid
) RETURNS catalog_booking_grant_receipts
LANGUAGE plpgsql AS $$
DECLARE
    receipt catalog_booking_grant_receipts%ROWTYPE;
    grant_row catalog_revisions%ROWTYPE;
    attributes jsonb;
    evaluated_at timestamptz;
BEGIN
    IF requested_operation IS NULL OR requested_subject IS NULL
        OR requested_product IS NULL OR requested_grant IS NULL THEN
        RAISE EXCEPTION 'Booking grant requires an operation, subject, product and grant' USING ERRCODE = '22023';
    END IF;

    -- Serialize retries before checking the grant, including retries after supersession.
    PERFORM pg_advisory_xact_lock(hashtextextended('catalog-booking:' || requested_operation::text, 0));
    SELECT * INTO receipt FROM catalog_booking_grant_receipts WHERE operation_id = requested_operation;
    IF FOUND THEN
        IF receipt.subject_id <> requested_subject OR receipt.product_revision_id <> requested_product
            OR receipt.grant_revision_id <> requested_grant THEN
            RAISE EXCEPTION 'Booking operation was already bound to different facts' USING ERRCODE = '23505';
        END IF;
        RETURN receipt;
    END IF;

    SELECT * INTO grant_row FROM catalog_revisions WHERE id = requested_grant FOR SHARE;
    evaluated_at := clock_timestamp();
    attributes := grant_row.content -> 'attributes';
    IF NOT FOUND OR grant_row.schema_id <> 'org.openbank.market.booking-grant'
        OR grant_row.schema_version <> 1 OR grant_row.state <> 'PUBLISHED'
        OR grant_row.approval_digest IS NULL OR grant_row.effective_from IS NULL
        OR grant_row.effective_from > evaluated_at
        OR (grant_row.effective_to IS NOT NULL AND grant_row.effective_to <= evaluated_at)
        OR (attributes ->> 'productRevisionId') IS DISTINCT FROM requested_product::text
        OR (attributes ->> 'mode' <> 'DOMESTIC'
            AND (attributes ->> 'hostCountry') IS NOT DISTINCT FROM (attributes ->> 'homeCountry')) THEN
        RAISE EXCEPTION 'Booking grant is unavailable for the requested product and time' USING ERRCODE = '22023';
    END IF;

    INSERT INTO catalog_booking_grant_receipts (
        operation_id, subject_id, product_revision_id, grant_revision_id,
        approval_digest, booked_context, consumed_at
    ) VALUES (
        requested_operation, requested_subject, requested_product, requested_grant,
        grant_row.approval_digest, attributes, evaluated_at
    ) RETURNING * INTO receipt;

    RETURN receipt;
END;
$$;

CREATE FUNCTION catalog_protect_booking_grant_receipt() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Committed booking grant receipts are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER catalog_booking_grant_receipt_immutable
BEFORE UPDATE OR DELETE ON catalog_booking_grant_receipts
FOR EACH ROW EXECUTE FUNCTION catalog_protect_booking_grant_receipt();
