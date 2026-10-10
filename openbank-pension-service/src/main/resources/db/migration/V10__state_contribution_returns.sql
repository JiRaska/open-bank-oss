-- ADR-0334 / #12382: CZ state-contribution returns (vratky, ZDPS 427/2011 §18) and the monthly
-- return reports that carry them to the Ministry of Finance.
--
-- Rollback: DROP TABLE pension_state_contribution_return_months; DROP TABLE pension_state_contribution_returns;
--   DROP TABLE pension_return_reports;
-- (nothing else references them; open returns would then have to be tracked by hand).

CREATE TABLE pension_return_reports (
    id                 UUID PRIMARY KEY,
    month              CHAR(7) NOT NULL,
    return_ids         TEXT NOT NULL,
    payload            TEXT NOT NULL,
    channel_reference  VARCHAR(128),
    result_applied     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL
);

CREATE TABLE pension_state_contribution_returns (
    id             UUID PRIMARY KEY,
    contract_id    UUID NOT NULL REFERENCES pension_contracts (contract_id),
    claim_id       UUID REFERENCES pension_incentive_claims (id),
    cause          VARCHAR(32) NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL,
    currency       CHAR(3) NOT NULL,
    discovered_on  DATE NOT NULL,
    due_by         DATE NOT NULL,
    -- What triggered it (exit activity key, or the reversed claim): a replayed trigger inserts nothing.
    source_key     VARCHAR(200) NOT NULL UNIQUE,
    status         VARCHAR(16) NOT NULL,
    report_id      UUID REFERENCES pension_return_reports (id),
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT pension_sc_returns_positive CHECK (amount > 0),
    CONSTRAINT pension_sc_returns_cause_known CHECK (cause IN ('CONTRACT_TERMINATED', 'INELIGIBILITY_DISCOVERED')),
    CONSTRAINT pension_sc_returns_status_known CHECK (status IN ('DUE', 'REPORTED', 'CONFIRMED', 'SETTLED')),
    CONSTRAINT pension_sc_returns_reported_in_report CHECK (status = 'DUE' OR report_id IS NOT NULL)
);

CREATE INDEX idx_pension_sc_returns_status ON pension_state_contribution_returns (status);
CREATE INDEX idx_pension_sc_returns_contract ON pension_state_contribution_returns (contract_id);

-- One return per (contract, contribution month), whatever its cause: an exit and an ineligibility
-- finding can never both owe the same month back to MF (the primary key is the guard).
CREATE TABLE pension_state_contribution_return_months (
    contract_id  UUID NOT NULL REFERENCES pension_contracts (contract_id),
    claim_month  CHAR(7) NOT NULL,
    return_id    UUID NOT NULL REFERENCES pension_state_contribution_returns (id),
    amount       NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (contract_id, claim_month)
);
