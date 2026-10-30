-- ADR-0239: own correlation ids before publish so early shared-topic outcomes can be applied safely.
-- Rollback: stop campaign sends first so new markers cannot race the drain/check. Reconcile every
-- campaign-owned handoff, retain resulting send_log evidence under the five-year campaign retention
-- policy, and drop this table only after no unresolved markers remain.
CREATE TABLE campaign_send_handoff_intents (
    send_id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES campaigns (id),
    party_id UUID NOT NULL,
    step_order INT NOT NULL,
    channel TEXT NOT NULL CHECK (channel IN ('EMAIL', 'PUSH')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivery_status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (delivery_status IN ('PENDING', 'CONFIRMED', 'SUPPRESSED', 'FAILED')),
    delivery_updated_at TIMESTAMPTZ,
    CHECK ((delivery_status = 'PENDING') = (delivery_updated_at IS NULL))
);
