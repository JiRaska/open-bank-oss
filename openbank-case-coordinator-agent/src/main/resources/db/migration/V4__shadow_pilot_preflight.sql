CREATE TABLE case_shadow_pilot_preflight (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
