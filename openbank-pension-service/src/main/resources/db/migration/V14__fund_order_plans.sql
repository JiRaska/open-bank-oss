-- Persist the complete instruction before the first fund-register request. Retries must not
-- derive a different instruction from changed strategy allocations, NAV or settled holdings.
-- Additive migration; no existing row is changed.
-- Rollback: stop fund writes and retries before downgrading the adapter. Retain this table until
-- all saved plans have been reconciled; dropping it while retries exist loses their instructions.
CREATE TABLE pension_fund_order_plans (
    operation_key CHAR(64) PRIMARY KEY,
    contract_id UUID NOT NULL,
    operation_kind VARCHAR(16) NOT NULL CHECK (operation_kind IN ('SUBSCRIBE', 'REDEEM')),
    amount NUMERIC NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    legs JSONB NOT NULL CHECK (jsonb_typeof(legs) = 'array' AND jsonb_array_length(legs) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
