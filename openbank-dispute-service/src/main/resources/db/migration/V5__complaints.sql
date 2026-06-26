-- Complaint aggregate (ADR-0085 §1, §2). Regulatory complaints with a statutory deadline clock.
-- Reuses the existing dispute_outbox table/dispatcher for complaint.* events (no new outbox).
-- Rollback: DROP TABLE complaints; DROP TYPE complaint_status; DROP TYPE complaint_channel; DROP TYPE complaint_category;

CREATE TYPE complaint_category AS ENUM ('PAYMENT_SERVICE', 'FEES', 'ACCOUNT_SERVICE', 'LENDING', 'CONDUCT', 'DATA_PROTECTION', 'OTHER');
CREATE TYPE complaint_channel AS ENUM ('APP', 'BRANCH', 'EMAIL', 'ARBITER');
CREATE TYPE complaint_status AS ENUM ('RECEIVED', 'RESOLVED', 'CLOSED');

CREATE TABLE complaints (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference            VARCHAR(32) UNIQUE NOT NULL,
    category             complaint_category NOT NULL,
    channel              complaint_channel NOT NULL,
    description          TEXT NOT NULL,
    status               complaint_status NOT NULL DEFAULT 'RECEIVED',
    account_id           UUID,
    transaction_id       UUID,
    dispute_id           UUID,
    received_date        DATE NOT NULL,
    due_date             DATE NOT NULL,
    interim_reply_at     TIMESTAMPTZ,
    interim_reply_reason TEXT,
    resolved_at          TIMESTAMPTZ,
    outcome              TEXT,
    redress_granted      BOOLEAN,
    root_cause_code      VARCHAR(64),
    closed_at            TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_complaints_status ON complaints(status);
CREATE INDEX idx_complaints_due_date ON complaints(due_date);
CREATE INDEX idx_complaints_account ON complaints(account_id);
CREATE INDEX idx_complaints_dispute ON complaints(dispute_id);
CREATE INDEX idx_complaints_received_date ON complaints(received_date);
