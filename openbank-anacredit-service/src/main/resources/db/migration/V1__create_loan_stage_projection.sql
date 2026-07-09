-- SPDX-License-Identifier: Apache-2.0
-- anacredit-service's first persisted state (ADR-0037 event-ingestion follow-up, issue #638):
-- a durable "last known IFRS 9 stage per loan" projection, populated by LoanStageEventConsumer from
-- lending-service's loan.stage_changed event. This does NOT touch the existing AnaCredit credit-dataset
-- model (CreditExposure remains in-memory in this increment) — it is a narrow, additive table scoped
-- specifically to the stage projection.
--
-- Rollback: DROP TABLE loan_stage_projection;

CREATE TABLE loan_stage_projection (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id             UUID NOT NULL UNIQUE,
    stage               VARCHAR(16) NOT NULL,
    days_past_due       INTEGER NOT NULL,
    event_timestamp     TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loan_stage_projection_loan_id ON loan_stage_projection(loan_id);

-- No Hibernate "<table>_seq" needed here (unlike V3 in lending-service / V6 in party-service): the
-- entity assigns its UUID id client-side (Ids.newId()) before persist, exactly like
-- LoanProvisioningEntity, so Panache's sequence-based id generator is never invoked.
