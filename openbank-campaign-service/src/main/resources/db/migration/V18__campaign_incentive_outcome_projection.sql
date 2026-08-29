-- No customer, promo code or digest is retained: Campaign Studio needs aggregate outcomes only.
-- Rollback: DROP TABLE campaign_incentive_outcome; this derived projection can be rebuilt from
-- retained incentive v2 events after restoring a compatible consumer.
CREATE TABLE campaign_incentive_outcome (
    event_id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL,
    campaign_id UUID NOT NULL REFERENCES campaigns(id),
    step_order INTEGER NOT NULL CHECK (step_order >= 0),
    attribution_ref UUID NOT NULL,
    offer_id UUID NOT NULL,
    offer_name VARCHAR(255) NOT NULL,
    offer_version INTEGER NOT NULL CHECK (offer_version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED', 'COMMITTED', 'RELEASED', 'EXPIRED')),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT campaign_incentive_reservation_status_uk UNIQUE (reservation_id, status)
);

CREATE INDEX campaign_incentive_outcome_funnel_idx
    ON campaign_incentive_outcome (campaign_id, status);
