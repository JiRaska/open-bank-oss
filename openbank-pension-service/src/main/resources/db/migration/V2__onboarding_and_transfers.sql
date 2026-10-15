-- ADR-0334 slice S2: digital onboarding applications, suitability assessments and transfer requests.
-- Each aggregate is stored as a JSON payload plus the columns that are queried or constrained;
-- `version` is the optimistic-lock counter the repositories compare under a row lock.
-- Rollback:
--   (constraint) restore V1's pension_contracts_start_date_when_active without 'CLOSED';
--   DROP TABLE pension_transfer_requests; DROP TABLE pension_suitability_assessments;
--   DROP TABLE pension_onboarding_applications;
--   DROP SEQUENCE pension_transfer_requests_seq; DROP SEQUENCE pension_suitability_assessments_seq;
--   DROP SEQUENCE pension_onboarding_applications_seq;

-- A contract withdrawn in the cooling-off period, or never funded, is CLOSED without ever having
-- started; V1's constraint (and the aggregate's init block, changed alongside) did not allow that.
ALTER TABLE pension_contracts DROP CONSTRAINT pension_contracts_start_date_when_active;
ALTER TABLE pension_contracts ADD CONSTRAINT pension_contracts_start_date_when_active CHECK (
    status IN ('DRAFT', 'PENDING_ACTIVATION', 'CLOSED') OR start_date IS NOT NULL);

CREATE TABLE pension_onboarding_applications (
    id                    BIGINT PRIMARY KEY,
    application_id        UUID NOT NULL UNIQUE,
    party_id              UUID NOT NULL,
    status                VARCHAR(32) NOT NULL,
    contract_id           UUID REFERENCES pension_contracts (contract_id),
    transfer_request_id   UUID,
    payload               TEXT NOT NULL,
    version               BIGINT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_onboarding_status_known CHECK (status IN (
        'STARTED', 'QUESTIONNAIRE_SUBMITTED', 'KID_ISSUED', 'KID_ACCEPTED', 'SIGNED', 'ACTIVATED',
        'REJECTED', 'WITHDRAWN', 'EXPIRED', 'TRANSFER_FAILED', 'ABANDONED')),
    -- Mirrors the aggregate: once signed, an application always names its contract.
    CONSTRAINT pension_onboarding_signed_has_contract CHECK (
        status IN ('STARTED', 'QUESTIONNAIRE_SUBMITTED', 'KID_ISSUED', 'KID_ACCEPTED', 'REJECTED', 'ABANDONED')
        OR (status = 'EXPIRED' AND contract_id IS NULL)
        OR contract_id IS NOT NULL)
);

CREATE INDEX idx_pension_onboarding_party ON pension_onboarding_applications (party_id, status);
CREATE INDEX idx_pension_onboarding_status ON pension_onboarding_applications (status, updated_at);
CREATE UNIQUE INDEX uq_pension_onboarding_transfer ON pension_onboarding_applications (transfer_request_id)
    WHERE transfer_request_id IS NOT NULL;

-- Append-only apart from CURRENT -> SUPERSEDED: the answers in force at signature are evidence.
CREATE TABLE pension_suitability_assessments (
    id               BIGINT PRIMARY KEY,
    assessment_id    UUID NOT NULL UNIQUE,
    party_id         UUID NOT NULL,
    application_id   UUID NOT NULL REFERENCES pension_onboarding_applications (application_id),
    status           VARCHAR(16) NOT NULL,
    payload          TEXT NOT NULL,
    assessed_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_suitability_status_known CHECK (status IN ('CURRENT', 'SUPERSEDED'))
);

CREATE INDEX idx_pension_suitability_application ON pension_suitability_assessments (application_id, assessed_at);

CREATE TABLE pension_transfer_requests (
    id            BIGINT PRIMARY KEY,
    transfer_id   UUID NOT NULL UNIQUE,
    direction     VARCHAR(8) NOT NULL,
    contract_id   UUID NOT NULL REFERENCES pension_contracts (contract_id),
    party_id      UUID NOT NULL,
    status        VARCHAR(24) NOT NULL,
    payload       TEXT NOT NULL,
    version       BIGINT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_transfer_direction_known CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT pension_transfer_status_known CHECK (status IN (
        'AWAITING_CONSENT', 'REQUESTED', 'SENT', 'ACCEPTED', 'FUNDS_RECEIVED', 'VALUATED', 'SETTLED', 'COMPLETED',
        'REJECTED', 'TIMED_OUT', 'CANCELLED', 'FAILED'))
);

CREATE INDEX idx_pension_transfer_contract ON pension_transfer_requests (contract_id, direction, status);
CREATE INDEX idx_pension_transfer_status ON pension_transfer_requests (status, updated_at);
-- At most one transfer-out in flight per contract, enforced by the database and not only by the
-- service's check-then-act.
CREATE UNIQUE INDEX uq_pension_transfer_out_open ON pension_transfer_requests (contract_id)
    WHERE direction = 'OUT' AND status IN ('AWAITING_CONSENT', 'REQUESTED', 'VALUATED', 'SETTLED');

CREATE SEQUENCE IF NOT EXISTS pension_onboarding_applications_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS pension_suitability_assessments_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS pension_transfer_requests_seq INCREMENT BY 50;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
