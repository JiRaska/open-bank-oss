-- Delegation-grant enforcement projection (ADR-0232 D3).
-- Fed by DelegationEventConsumer from openbank.delegation.events; account-service
-- enforces owner OR grant locally, never calling delegation-service on the request path.

CREATE TABLE account_delegation_projection (
    id                      UUID PRIMARY KEY,
    account_id              UUID NOT NULL,
    -- The grantor is not decoration: the guard compares it to the account's owner, because a
    -- projection row keyed only on (account_id, grantee_party_id) is authority in itself and a
    -- grant naming a stranger's account would therefore be enforced against that account.
    grantor_party_id        UUID NOT NULL,
    grantee_party_id        UUID NOT NULL,
    per_tx_limit_amount     NUMERIC(20, 6),
    per_tx_limit_currency   CHAR(3),
    valid_from              TIMESTAMPTZ NOT NULL,
    valid_to                TIMESTAMPTZ,
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE account_delegation_projection_caps (
    grant_id    UUID NOT NULL REFERENCES account_delegation_projection(id) ON DELETE CASCADE,
    capability  VARCHAR(100) NOT NULL,
    PRIMARY KEY (grant_id, capability)
);

CREATE INDEX idx_delegation_projection_guard
    ON account_delegation_projection(account_id, grantee_party_id, active);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
