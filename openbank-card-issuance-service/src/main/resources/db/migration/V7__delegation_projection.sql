-- Delegation-grant enforcement projection for cards (ADR-0232 D3).
-- Fed by CardDelegationEventConsumer from openbank.delegation.events.

CREATE TABLE card_delegation_projection (
    id                  UUID PRIMARY KEY,
    card_id             UUID NOT NULL,
    -- The party who ISSUED the grant. The guard requires it to be the card's own holder, so a
    -- row cannot authorise on its own existence (#3164 C2; #3143 does the same on accounts).
    grantor_party_id    UUID NOT NULL,
    grantee_party_id    UUID NOT NULL,
    valid_from          TIMESTAMPTZ NOT NULL,
    valid_to            TIMESTAMPTZ,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE card_delegation_projection_caps (
    grant_id    UUID NOT NULL REFERENCES card_delegation_projection(id) ON DELETE CASCADE,
    capability  VARCHAR(100) NOT NULL,
    PRIMARY KEY (grant_id, capability)
);

CREATE INDEX idx_card_delegation_projection_guard
    ON card_delegation_projection(card_id, grantee_party_id, active);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
