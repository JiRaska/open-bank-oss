CREATE TABLE case_shadow_pilot_preflight (
    rollout_id VARCHAR(128) PRIMARY KEY,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
