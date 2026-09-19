-- A human-opened investigation case anchored to one immutable REVIEW score.
-- A score is only a lead, never a fraud finding. The case has its own revision and
-- cannot be inferred from a temporary marketing hold.
CREATE TABLE fraud_investigation_cases (
    case_id UUID PRIMARY KEY,
    score_id UUID NOT NULL UNIQUE REFERENCES fraud_scores(score_id),
    account_id UUID NOT NULL,
    counterparty_id UUID,
    status VARCHAR(32) NOT NULL CHECK (status IN ('OPEN', 'CLOSED_NO_FINDING')),
    revision BIGINT NOT NULL CHECK (revision > 0),
    opened_by VARCHAR(128) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_by VARCHAR(128),
    closed_at TIMESTAMPTZ,
    CONSTRAINT chk_fraud_case_closed_fields CHECK (
        (status = 'OPEN' AND closed_by IS NULL AND closed_at IS NULL) OR
        (status = 'CLOSED_NO_FINDING' AND closed_by IS NOT NULL AND closed_at IS NOT NULL)
    )
);

CREATE INDEX idx_fraud_investigation_cases_status ON fraud_investigation_cases(status, opened_at DESC);
GRANT ALL ON fraud_investigation_cases TO openbank;

-- Rollback before any case is opened: DROP TABLE fraud_investigation_cases;
-- After use, retain case history and outbox events for evidentiary replay;
-- do not drop the table on a code rollback.
