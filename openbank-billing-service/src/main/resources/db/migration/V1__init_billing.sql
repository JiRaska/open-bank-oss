-- ADR-0143 phase 2c: persistence for a billing cycle's fee assessments and their posting
-- intent. One row per assessed fee (never per account) so a multi-fee product's charges are
-- independently auditable and independently postable — the same "feeId dimension" reasoning
-- that shapes the idempotency key (com.openbank.billing.domain.AssessedFee.idempotencyKey).

CREATE TYPE billing_posting_status AS ENUM ('NOT_APPLICABLE', 'PENDING', 'POSTED', 'FAILED');

-- One row per (cycleId, accountId, currency) assessment run — mirrors BillingAssessment.
-- `skipped` + `skip_reason` persist the fail-closed flag (ADR-0143 D5): an account whose
-- FeeContext could not be resolved is flagged here, never silently dropped.
CREATE TABLE billing_cycle_assessment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id        VARCHAR(64) NOT NULL,
    account_id      VARCHAR(64) NOT NULL,
    currency        CHAR(3) NOT NULL,
    skipped         BOOLEAN NOT NULL DEFAULT FALSE,
    skip_reason     VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_billing_cycle_assessment UNIQUE (cycle_id, account_id, currency)
);

-- One row per assessed fee (AssessedFee) — the idempotency key's natural columns
-- (cycle_id, account_id, fee_id, currency) are individually indexed/constrained so a
-- re-run of the same cycle for the same account never inserts a second row (assess is
-- idempotent per ADR-0143 step 1) and so the DST invariant (phase 2d) can sum by these
-- dimensions directly against billing_fee_journal.
CREATE TABLE assessed_fee (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id       UUID NOT NULL REFERENCES billing_cycle_assessment(id),
    cycle_id            VARCHAR(64) NOT NULL,
    account_id          VARCHAR(64) NOT NULL,
    fee_id              VARCHAR(64) NOT NULL,
    fee_name            VARCHAR(200) NOT NULL,
    currency            CHAR(3) NOT NULL,
    charged_amount      NUMERIC(20,4) NOT NULL,
    waived              BOOLEAN NOT NULL DEFAULT FALSE,
    waive_reason        VARCHAR(64) NOT NULL,
    idempotency_key      VARCHAR(160) NOT NULL,
    -- Posting status/journal id (ADR-0143 step 2): NOT_APPLICABLE for waived/zero-amount
    -- fees that never post a journal; PENDING once the outbox row is appended in the same
    -- transaction; POSTED once the dispatcher's ledger call succeeds; FAILED on a
    -- terminal (DEAD) outbox row so an operator can see a fee never reached the ledger.
    posting_status      billing_posting_status NOT NULL DEFAULT 'NOT_APPLICABLE',
    journal_id          UUID,
    posted_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_assessed_fee_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uq_assessed_fee_natural_key UNIQUE (cycle_id, account_id, fee_id, currency)
);

CREATE INDEX idx_assessed_fee_assessment_id ON assessed_fee(assessment_id);
CREATE INDEX idx_assessed_fee_account_id ON assessed_fee(account_id);
CREATE INDEX idx_assessed_fee_posting_status ON assessed_fee(posting_status);
CREATE INDEX idx_billing_cycle_assessment_cycle_id ON billing_cycle_assessment(cycle_id);
CREATE INDEX idx_billing_cycle_assessment_skipped ON billing_cycle_assessment(skipped) WHERE skipped = TRUE;
