ALTER TABLE parties
    ADD COLUMN case_id UUID,
    ADD COLUMN case_type VARCHAR(50),
    ADD COLUMN case_status VARCHAR(40),
    ADD COLUMN case_last_actor VARCHAR(100),
    ADD COLUMN case_last_reason_code VARCHAR(40),
    ADD COLUMN case_last_transition_at TIMESTAMPTZ,
    ADD COLUMN case_metadata TEXT;

ALTER TABLE parties
    ADD CONSTRAINT chk_pid_case_type CHECK (case_type IS NULL OR case_type = 'PID_VERIFICATION'),
    ADD CONSTRAINT chk_pid_case_status CHECK (
        case_status IS NULL OR case_status IN (
            'DRAFT','OPEN','IN_REVIEW','WAITING_FOR_CUSTOMER','WAITING_FOR_EXTERNAL_PARTY',
            'APPROVED','REJECTED','CLOSED','CANCELLED'
        )
    ),
    ADD CONSTRAINT chk_pid_case_reason CHECK (
        case_last_reason_code IS NULL OR case_last_reason_code IN (
            'CREATED','REVIEW_STARTED','INFORMATION_REQUESTED','INFORMATION_RECEIVED','EXTERNAL_DEPENDENCY',
            'APPROVED','REJECTED','CLOSED','CANCELLED','REOPENED','MANUAL_UPDATE'
        )
    );

CREATE INDEX idx_parties_case_id ON parties (case_id);
CREATE INDEX idx_parties_case_status ON parties (case_status) WHERE case_status IS NOT NULL;
