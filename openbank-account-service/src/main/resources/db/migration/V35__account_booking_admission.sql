-- SPDX-License-Identifier: Apache-2.0
-- Keep pending operations and booking receipts outside the accounts table to avoid an
-- index build or validation scan of existing customer rows. Existing accounts have no receipt.
-- Rollback: stop writers first and leave both tables in place as historical evidence.

CREATE TABLE account_booking_operations (
    operation_id UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    party_id UUID NOT NULL,
    product_id UUID NOT NULL,
    product_revision_id UUID NOT NULL,
    grant_revision_id UUID NOT NULL CHECK (grant_revision_id <> product_revision_id),
    product_revision_pin JSONB NOT NULL CHECK (
        jsonb_typeof(product_revision_pin) = 'object'
        AND (product_revision_pin ->> 'revisionId') IS NOT DISTINCT FROM product_revision_id::text
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE account_booking_receipts (
    account_id UUID PRIMARY KEY REFERENCES accounts(id),
    operation_id UUID NOT NULL UNIQUE REFERENCES account_booking_operations(operation_id),
    legal_entity_id UUID NOT NULL,
    booking_book_id UUID NOT NULL,
    operating_mode VARCHAR(8) NOT NULL CHECK (operating_mode IN ('DOMESTIC', 'FOS', 'BRANCH')),
    establishment_id UUID,
    approval_digest VARCHAR(64) NOT NULL CHECK (approval_digest ~ '^[0-9a-f]{64}$'),
    consumed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_account_booking_establishment
        CHECK ((operating_mode = 'BRANCH') = (establishment_id IS NOT NULL))
);

-- The account, its existing idempotency record and the receipt must commit together.
-- Resolve through the durable operation to prevent attaching a receipt to another
-- party, product, Product as Code revision, or account-opening request.
CREATE FUNCTION account_booking_require_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1
    FROM account_booking_operations operation
    JOIN account_idempotency idem ON idem.idempotency_key = operation.idempotency_key
        AND idem.request_hash = operation.request_hash
    JOIN accounts account ON account.id = idem.account_id
        AND account.party_id = operation.party_id
        AND account.product_id = operation.product_id
        AND account.product_revision_id = operation.product_revision_id
        AND account.product_revision_ir_hash IS NOT DISTINCT FROM
            (operation.product_revision_pin ->> 'irRevisionHash')
    WHERE operation.operation_id = NEW.operation_id AND account.id = NEW.account_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Booking receipt does not match the committed account-opening identity'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_booking_receipt_binding
BEFORE INSERT ON account_booking_receipts
FOR EACH ROW EXECUTE FUNCTION account_booking_require_binding();

CREATE FUNCTION account_booking_reject_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Account booking evidence is immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_booking_operation_immutable
BEFORE UPDATE OR DELETE ON account_booking_operations
FOR EACH ROW EXECUTE FUNCTION account_booking_reject_change();

CREATE TRIGGER account_booking_receipt_immutable
BEFORE UPDATE OR DELETE ON account_booking_receipts
FOR EACH ROW EXECUTE FUNCTION account_booking_reject_change();
