-- #12317: a committed domestic co-signature receipt resolves an ambiguous customer-edge submit.
-- Rollback: revert the receipt-aware application and retain this table and function as committed evidence.
-- The deferred FK allows the unique correlation claim to precede the approval insert in one transaction.
CREATE TABLE approval_submission_receipts (
    correlation_digest VARCHAR(64) PRIMARY KEY CHECK (correlation_digest ~ '^[0-9a-f]{64}$'),
    approval_request_id UUID NOT NULL UNIQUE,
    CONSTRAINT fk_approval_submission_request FOREIGN KEY (approval_request_id)
        REFERENCES approval_requests (id) DEFERRABLE INITIALLY DEFERRED
);

CREATE FUNCTION refuse_approval_submission_receipt_change() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'approval submission receipts are immutable';
END;
$$;

CREATE TRIGGER approval_submission_receipt_immutable
BEFORE UPDATE OR DELETE ON approval_submission_receipts
FOR EACH ROW EXECUTE FUNCTION refuse_approval_submission_receipt_change();
