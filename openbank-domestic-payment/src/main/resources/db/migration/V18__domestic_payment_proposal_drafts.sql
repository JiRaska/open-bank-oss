-- Expand-only: a maker's draft is never an executable domestic payment. Approval snapshots and
-- decisions will be added before any transition from DRAFT can create a payment instruction.
CREATE TABLE domestic_payment_proposal_drafts (
    id BIGSERIAL PRIMARY KEY,
    proposal_id UUID NOT NULL UNIQUE,
    maker_party_id UUID NOT NULL,
    owner_party_id UUID NOT NULL,
    delegation_id UUID NOT NULL,
    debtor_account_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    instruction_json TEXT NOT NULL,
    amount NUMERIC(20, 6) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    creditor_account_number VARCHAR(34) NOT NULL,
    creditor_bank_code VARCHAR(4) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_domestic_payment_proposal_maker_key UNIQUE (maker_party_id, idempotency_key),
    CONSTRAINT ck_domestic_payment_proposal_distinct_maker CHECK (maker_party_id <> owner_party_id),
    CONSTRAINT ck_domestic_payment_proposal_positive_amount CHECK (amount > 0),
    CONSTRAINT ck_domestic_payment_proposal_window CHECK (expires_at > created_at),
    CONSTRAINT ck_domestic_payment_proposal_draft_status CHECK (status = 'DRAFT')
);

-- Hibernate Reactive's PanacheEntity allocates blocks of 50 from <table>_seq, not BIGSERIAL's
-- <table>_id_seq. Both must exist while this draft entity is mapped.
CREATE SEQUENCE domestic_payment_proposal_drafts_seq INCREMENT BY 50;

CREATE INDEX idx_domestic_payment_proposal_owner_created
    ON domestic_payment_proposal_drafts (owner_party_id, created_at DESC);
CREATE INDEX idx_domestic_payment_proposal_expiry
    ON domestic_payment_proposal_drafts (expires_at)
    WHERE status = 'DRAFT';
