-- SPDX-License-Identifier: Apache-2.0
-- ADR-0333: persist the intent before the Temporal start for direct and triggered enrolment.
-- Rollback: stop recovery and new admissions, reconcile pending rows against Temporal and
-- enrolments, then drop this table. Never drop unresolved intents during a live rollout.
CREATE TABLE campaign_journey_start_intents (
    campaign_id UUID NOT NULL REFERENCES campaigns (id),
    party_id UUID NOT NULL,
    enrolment_id UUID NOT NULL,
    journey_type TEXT NOT NULL CHECK (journey_type IN ('LINEAR', 'DECISION_GRAPH')),
    source TEXT NOT NULL CHECK (source IN ('DIRECT', 'TRIGGER')),
    experiment_cohort TEXT NOT NULL CHECK (experiment_cohort = 'TREATMENT'),
    content_variant TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    PRIMARY KEY (campaign_id, party_id)
);
CREATE INDEX idx_campaign_journey_start_intents_recovery
    ON campaign_journey_start_intents (created_at)
    WHERE lease_until IS NULL;
