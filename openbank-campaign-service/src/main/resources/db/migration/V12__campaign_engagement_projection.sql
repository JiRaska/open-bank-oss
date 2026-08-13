-- Aggregate-only campaign in-app reporting (#4480).  This projection deliberately excludes
-- party_id: the consumer validates ownership upstream, while Campaign Studio only needs event
-- counts.  event_id makes at-least-once Kafka delivery idempotent.
--
-- Rollback: DROP TABLE campaign_engagement_event; this is a derived read model and can be rebuilt
-- from retained engagement events if a deployment is rolled back.
CREATE TABLE campaign_engagement_event (
    event_id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL,
    step_order INTEGER NOT NULL CHECK (step_order >= 0),
    channel VARCHAR(8) NOT NULL CHECK (channel IN ('PUSH', 'BANNER')),
    surface VARCHAR(32) NOT NULL CHECK (surface IN ('HOME_BANNER', 'HOME_CAROUSEL', 'PRODUCT_FEED', 'REWARDS_HUB')),
    type VARCHAR(16) NOT NULL CHECK (type IN ('IMPRESSION', 'CLICK', 'DISMISS')),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX campaign_engagement_event_campaign_step_idx
    ON campaign_engagement_event (campaign_id, step_order, channel, surface, type);
