-- An accepted allocation is a single provider transaction: the receipt and every leg commit
-- together. A repeated batch key returns this exact response even after a fund closes.
-- Rollback: retain this receipt and its replay code for the entire idempotency window;
-- deleting it while retries remain could permit a different allocation under the same key.
CREATE TABLE subscription_order_batches (
    contract_id UUID NOT NULL,
    batch_key VARCHAR(512) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (contract_id, batch_key),
    CONSTRAINT subscription_order_batches_response_array CHECK (jsonb_typeof(response) = 'array')
);

GRANT ALL ON subscription_order_batches TO openbank;
