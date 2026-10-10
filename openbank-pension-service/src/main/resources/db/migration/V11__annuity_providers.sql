-- #12383 (ADR-0334): partner-agnostic annuity integration.
--
-- pension_annuity_providers: the partner registry (DATA, not code). The columns the service queries
-- by are real columns; the terms are a JSON body. row_version is the optimistic lock (four-eyes
-- approval racing an amendment loses with 409 instead of activating terms nobody checked).
--
-- pension_annuity_purchases: one row per ANNUITY payout (aggregate_id = the payout id): the offers
-- presented, the SCA-signed selection, the application, premium, policy and any compensation.
--
-- pension_payout_requests.status gains REVERSED: an annuity whose premium the pack returned to the
-- contract.
--
-- Rollback (only while no purchase reached REVERSED — otherwise migrate those rows first):
--   ALTER TABLE pension_payout_requests DROP CONSTRAINT pension_payout_requests_status_known;
--   ALTER TABLE pension_payout_requests ADD CONSTRAINT pension_payout_requests_status_known CHECK (status IN (
--       'QUOTED', 'CONFIRMED', 'IN_PAYMENT', 'COMPLETED', 'EXPIRED'));
--   DROP TABLE pension_annuity_purchases; DROP TABLE pension_annuity_providers;
--   DROP SEQUENCE pension_annuity_purchases_seq; DROP SEQUENCE pension_annuity_providers_seq;

-- Panache ids draw from <table>_seq in blocks of 50 (as V4).
CREATE SEQUENCE IF NOT EXISTS pension_annuity_providers_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS pension_annuity_purchases_seq INCREMENT BY 50;

CREATE TABLE pension_annuity_providers (
    id           BIGSERIAL PRIMARY KEY,
    partner_id   VARCHAR(40) NOT NULL UNIQUE,
    status       VARCHAR(24) NOT NULL,
    body         TEXT NOT NULL,
    row_version  INTEGER NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_annuity_providers_status_known CHECK (status IN (
        'DRAFT', 'PENDING_ACTIVATION', 'ACTIVE', 'DISABLED'))
);
CREATE INDEX idx_pension_annuity_providers_status ON pension_annuity_providers (status);

CREATE TABLE pension_annuity_purchases (
    id               BIGSERIAL PRIMARY KEY,
    aggregate_id     UUID NOT NULL UNIQUE,
    contract_id      UUID NOT NULL REFERENCES pension_contracts (contract_id),
    status           VARCHAR(16) NOT NULL,
    idempotency_key  VARCHAR(128),
    body             TEXT NOT NULL,
    row_version      INTEGER NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_annuity_purchases_status_known CHECK (status IN (
        'OFFERED', 'SELECTED', 'APPLIED', 'PREMIUM_SENT', 'ACTIVE', 'CANCELLED', 'FAILED'))
);
CREATE INDEX idx_pension_annuity_purchases_status ON pension_annuity_purchases (status);
CREATE INDEX idx_pension_annuity_purchases_contract ON pension_annuity_purchases (contract_id);

ALTER TABLE pension_payout_requests DROP CONSTRAINT pension_payout_requests_status_known;
ALTER TABLE pension_payout_requests ADD CONSTRAINT pension_payout_requests_status_known CHECK (status IN (
    'QUOTED', 'CONFIRMED', 'IN_PAYMENT', 'COMPLETED', 'EXPIRED', 'REVERSED'));
