-- A refusal never creates authority and does not consume an approval SCA challenge.
-- The old V28 writer has no decision API, so nullable session is safe across mixed images.
-- Rollback: disable the new decision API and retain immutable rows; do not restore NOT NULL
-- while any REJECT evidence exists. A later contraction needs its own reviewed migration.
ALTER TABLE delegation_statutory_decisions
    ALTER COLUMN sca_session_id DROP NOT NULL;

ALTER TABLE delegation_statutory_decisions
    ADD CONSTRAINT chk_delegation_statutory_decision_sca CHECK (
        (decision = 'APPROVE' AND sca_session_id IS NOT NULL)
        OR (decision = 'REJECT' AND sca_session_id IS NULL)
    );
