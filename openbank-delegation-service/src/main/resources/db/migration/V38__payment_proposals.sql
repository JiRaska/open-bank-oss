-- ADR-0383 D6: the proposal store for the customer MCP channel. An MCP client may only PROPOSE a
-- payment; the customer approves it in the bank app with full SCA bound to payload_sha256, and the
-- payment is initiated by the customer's own session through the ordinary payment path. Nothing in
-- this table moves money.
--
-- V38 (not V35): open PRs already claim V35-V37 in this module; Flyway accepts the gap and the
-- number cannot collide on merge.
--
-- Rollback: a new forward migration `DROP TABLE delegation_payment_proposals;` together with
-- `DROP FUNCTION delegation_payment_proposal_guard(), delegation_payment_proposal_no_delete();`, after confirming no APPROVED rows exist
-- (an APPROVED row is a customer approval whose execution record would otherwise be lost). Never
-- edit this file after it has been applied.

CREATE TABLE delegation_payment_proposals (
    id                  UUID PRIMARY KEY,
    party_id            UUID NOT NULL,
    grant_id            UUID NOT NULL REFERENCES delegation_grants(id) ON DELETE RESTRICT,
    mcp_client_id       VARCHAR(255) NOT NULL,
    operation_type      VARCHAR(32) NOT NULL,
    debtor_account_id   UUID NOT NULL,
    creditor_iban       VARCHAR(34) NOT NULL,
    creditor_name       VARCHAR(70) NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL,
    currency            CHAR(3) NOT NULL,
    reference           VARCHAR(140),
    payload_sha256      CHAR(64) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    revision            BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    decided_at          TIMESTAMPTZ,
    executed_payment_id UUID,
    CONSTRAINT chk_payment_proposal_operation CHECK (operation_type IN ('DOMESTIC_PAYMENT', 'SEPA_PAYMENT')),
    CONSTRAINT chk_payment_proposal_status CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'EXECUTED', 'EXECUTION_FAILED')
    ),
    CONSTRAINT chk_payment_proposal_amount CHECK (amount > 0),
    CONSTRAINT chk_payment_proposal_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_payment_proposal_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_payment_proposal_executed CHECK ((status = 'EXECUTED') = (executed_payment_id IS NOT NULL)),
    CONSTRAINT chk_payment_proposal_decided CHECK ((status = 'PENDING') = (decided_at IS NULL))
);

-- The customer's inbox and the per-party limits read by party, newest first.
CREATE INDEX idx_payment_proposals_party ON delegation_payment_proposals (party_id, created_at DESC);

-- Defence in depth for the application rules, so a future writer cannot weaken them by accident:
--  * the instruction, its hash, the party, grant and client are IMMUTABLE once written;
--  * the state graph is one-way: PENDING -> APPROVED | REJECTED | EXPIRED,
--    APPROVED -> EXECUTED | EXECUTION_FAILED, and nothing else (so nothing returns to PENDING and a
--    proposal is one-shot); every transition advances the revision by exactly one.
CREATE FUNCTION delegation_payment_proposal_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.party_id <> OLD.party_id OR NEW.grant_id <> OLD.grant_id
        OR NEW.mcp_client_id <> OLD.mcp_client_id OR NEW.operation_type <> OLD.operation_type
        OR NEW.debtor_account_id <> OLD.debtor_account_id OR NEW.creditor_iban <> OLD.creditor_iban
        OR NEW.creditor_name <> OLD.creditor_name OR NEW.amount <> OLD.amount
        OR NEW.currency <> OLD.currency OR NEW.reference IS DISTINCT FROM OLD.reference
        OR NEW.payload_sha256 <> OLD.payload_sha256 OR NEW.created_at <> OLD.created_at
        OR NEW.expires_at <> OLD.expires_at THEN
        RAISE EXCEPTION 'payment proposal % is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NOT (
        (OLD.status = 'PENDING' AND NEW.status IN ('APPROVED', 'REJECTED', 'EXPIRED'))
        OR (OLD.status = 'APPROVED' AND NEW.status IN ('EXECUTED', 'EXECUTION_FAILED'))
    ) THEN
        RAISE EXCEPTION 'payment proposal % cannot move from % to %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.revision <> OLD.revision + 1 THEN
        RAISE EXCEPTION 'payment proposal % revision must advance by one', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payment_proposal_guard
    BEFORE UPDATE ON delegation_payment_proposals
    FOR EACH ROW EXECUTE FUNCTION delegation_payment_proposal_guard();

-- No DELETE: a proposal is evidence of what a connected client asked for. A raising trigger, not
-- a DO INSTEAD NOTHING rule: a rule would let the DELETE report success while doing nothing.
CREATE FUNCTION delegation_payment_proposal_no_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'payment proposals are append-only' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payment_proposal_no_delete
    BEFORE DELETE ON delegation_payment_proposals
    FOR EACH ROW EXECUTE FUNCTION delegation_payment_proposal_no_delete();
