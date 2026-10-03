-- Restrict a historical observation without rewriting the original source evidence.
-- A reason is a fixed code, never free text or owner attributes. A restriction is permanent
-- in this contract; correction requires a new observation after an independent review.
-- Rollback before use: DROP TABLE kyb_ubo_observation_restrictions;
-- After use retain restriction rows and roll back readers to fail closed; dropping this table
-- while evidence remains would re-expose restricted source data.
CREATE TABLE kyb_ubo_observation_restrictions (
    observation_id UUID PRIMARY KEY REFERENCES kyb_ubo_observations (observation_id),
    case_id UUID NOT NULL REFERENCES kyb_cases (case_id),
    reason_code VARCHAR(32) NOT NULL CHECK (reason_code IN (
        'SOURCE_WITHDRAWN', 'EVIDENCE_CHALLENGED', 'PRIVACY_RESTRICTION'
    )),
    actor_id VARCHAR(128) NOT NULL,
    restricted_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_kyb_ubo_restrictions_case ON kyb_ubo_observation_restrictions (case_id);
GRANT ALL ON kyb_ubo_observation_restrictions TO openbank;
