-- DRAFT storage only. No rail dispatch or signature state is represented here.
CREATE TABLE business_payment_batch_drafts (
    id UUID PRIMARY KEY,
    entity_party_id UUID NOT NULL,
    actor_party_id UUID NOT NULL,
    updated_by_party_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    debtor_account_id UUID NOT NULL,
    items_json TEXT NOT NULL,
    amount_minor BIGINT NOT NULL,
    item_count INTEGER NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_business_batch_idempotency UNIQUE (entity_party_id, idempotency_key),
    CONSTRAINT chk_business_batch_item_count CHECK (item_count BETWEEN 1 AND 100),
    CONSTRAINT chk_business_batch_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_business_batch_hash CHECK (length(request_hash) = 64)
);
CREATE INDEX idx_business_batch_company_created ON business_payment_batch_drafts (entity_party_id, created_at DESC, id DESC);
