-- ADR-0301 D1: customer-declared holdings the bank does not hold.
-- Rollback:
--   DROP TABLE wealth_outbox; DROP TABLE declared_holdings;
--   DROP SEQUENCE wealth_outbox_seq; DROP SEQUENCE declared_holdings_seq;

CREATE TABLE declared_holdings (
    id                   BIGSERIAL PRIMARY KEY,
    holding_id           UUID NOT NULL UNIQUE,
    owner_party_id       UUID NOT NULL,
    holding_type         VARCHAR(32) NOT NULL,
    label                VARCHAR(256) NOT NULL,
    valuation_amount     NUMERIC(19, 4) NOT NULL,
    valuation_currency   CHAR(3) NOT NULL,
    valued_at            DATE NOT NULL,
    valuation_source     VARCHAR(32) NOT NULL,
    appraiser_reference  VARCHAR(256),
    ownership_share      NUMERIC(9, 8) NOT NULL DEFAULT 1,
    external_reference   VARCHAR(128),
    document_ids         TEXT NOT NULL DEFAULT '[]',
    status               VARCHAR(16) NOT NULL,
    pledged_to_loan_id   UUID,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL
);

-- The natural key that makes a replayed declare a no-op. PARTIAL, because a null
-- external_reference means "the caller had nothing stable to name" — several such rows must be
-- allowed to coexist, and a plain UNIQUE would treat every null as distinct anyway while still
-- indexing them for nothing.
CREATE UNIQUE INDEX uq_declared_holdings_natural_key
    ON declared_holdings (owner_party_id, holding_type, external_reference)
    WHERE external_reference IS NOT NULL;

CREATE INDEX idx_declared_holdings_owner ON declared_holdings (owner_party_id, status);

-- PLEDGED and pledged_to_loan_id must agree, the same invariant the aggregate's init block
-- enforces. Stated in both places on purpose: the domain rule is what a unit test can prove, and
-- this constraint is what survives a future writer that bypasses the aggregate.
ALTER TABLE declared_holdings
    ADD CONSTRAINT declared_holdings_pledge_consistent
    CHECK ((status = 'PLEDGED') = (pledged_to_loan_id IS NOT NULL));

-- Transactional outbox (ADR-0003 / ADR-0050), same shape as the rest of the fleet.
CREATE TABLE wealth_outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    attempt_count   INT NOT NULL DEFAULT 0,
    last_error      TEXT,
    claimed_at      TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_wealth_outbox_status ON wealth_outbox (status, created_at);

-- #9081: guards against toEntity() assigning an epoch timestamp over the column DEFAULT, which
-- would sort ahead of all real work in the dispatcher's ORDER BY created_at ASC.
ALTER TABLE wealth_outbox
    ADD CONSTRAINT wealth_outbox_created_at_plausible
    CHECK (created_at >= TIMESTAMPTZ '2020-01-01');

-- Hibernate Reactive + PanacheEntity allocate ids from "<table>_seq" (allocationSize 50), which
-- BIGSERIAL does not create. Unquoted, lowercase, INCREMENT BY 50 — the repo convention after
-- party V19 / delegation V2 / kyb V1.
CREATE SEQUENCE IF NOT EXISTS declared_holdings_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS wealth_outbox_seq INCREMENT BY 50;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
