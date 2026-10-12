-- Freeze the selected strategy's exact allocation before placing any external fund order.
-- A retry, including after restart or a strategy election, reuses this plan.
-- Rollback: do not drop this table while any request can be retried or redelivered.
-- Stop new subscription writes, drain and reconcile every pending subscription (including
-- orders placed before their caller recorded completion), and retain the frozen plans until
-- the corresponding idempotency/replay window has ended. Prefer a forward fix if that
-- proof is unavailable; reverting the caller alone can reroute a repeated key.
CREATE TABLE pension_subscription_plans (
    contract_id UUID NOT NULL REFERENCES pension_contracts (contract_id),
    -- Producer payment IDs allow 128 chars and the contribution: prefix adds 13 more.
    idempotency_key VARCHAR(512) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    legs JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (contract_id, idempotency_key),
    CONSTRAINT pension_subscription_plan_amount_positive CHECK (amount > 0),
    CONSTRAINT pension_subscription_plan_legs_nonempty CHECK (jsonb_typeof(legs) = 'array' AND jsonb_array_length(legs) > 0)
);

GRANT ALL ON pension_subscription_plans TO openbank;
