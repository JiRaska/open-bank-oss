-- Rollback: disable operator origination, drain live approvals, and retain proposal/approval
-- evidence. Old approvals have no proposal; new writers must refuse to approve or consume them.
-- Never reconstruct an old instruction from its digest or overwrite a stored proposal.
CREATE TABLE settlement_operator_proposals (
    id UUID PRIMARY KEY,
    maker_id TEXT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    idempotency_key TEXT NOT NULL,
    payer_account_id UUID NOT NULL,
    payee_account_id UUID NOT NULL,
    amount TEXT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (maker_id, fingerprint),
    UNIQUE (id, maker_id, fingerprint),
    CHECK (payer_account_id <> payee_account_id)
);

ALTER TABLE settlement_operator_approvals ADD COLUMN proposal_id UUID;
ALTER TABLE settlement_operator_approvals ADD CONSTRAINT settlement_approval_proposal_binding
    FOREIGN KEY (proposal_id, maker_id, resource_id)
    REFERENCES settlement_operator_proposals (id, maker_id, fingerprint);

-- A retry may reuse the same proposal; it cannot edit it, even before a checker decides.
CREATE FUNCTION reject_settlement_proposal_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'settlement proposals are immutable';
END;
$$;
CREATE TRIGGER settlement_proposal_immutable BEFORE UPDATE ON settlement_operator_proposals
    FOR EACH ROW EXECUTE FUNCTION reject_settlement_proposal_update();
