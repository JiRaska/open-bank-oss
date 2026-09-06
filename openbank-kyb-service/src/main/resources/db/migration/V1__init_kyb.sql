-- ADR-0284: legal-entity verification and the business onboarding case.
-- Rollback:
--   DROP TABLE kyb_outbox; DROP TABLE kyb_registry_extracts; DROP TABLE kyb_cases;
--   DROP SEQUENCE kyb_outbox_seq; DROP SEQUENCE kyb_registry_extracts_seq; DROP SEQUENCE kyb_cases_seq;

CREATE TABLE kyb_cases (
    id                   BIGSERIAL PRIMARY KEY,
    case_id              UUID NOT NULL UNIQUE,
    identifier_scheme    VARCHAR(16) NOT NULL,
    identifier_value     VARCHAR(64) NOT NULL,
    initiator_party_id   UUID NOT NULL,
    status               VARCHAR(32) NOT NULL,
    extract_json         TEXT,
    entity_party_id      UUID,
    entity_party_active  BOOLEAN NOT NULL DEFAULT FALSE,
    required_signatures  INT,
    signers_json         TEXT NOT NULL DEFAULT '[]',
    invitation_tokens    TEXT,
    signer_party_ids     TEXT,
    review_reason        TEXT,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_kyb_cases_identifier ON kyb_cases (identifier_scheme, identifier_value);
CREATE INDEX idx_kyb_cases_initiator ON kyb_cases (initiator_party_id);
CREATE INDEX idx_kyb_cases_entity_party ON kyb_cases (entity_party_id) WHERE entity_party_id IS NOT NULL;
CREATE INDEX idx_kyb_cases_status ON kyb_cases (status, updated_at);

-- Short-lived cache of public-register extracts (one row per identifier, overwritten on refetch).
CREATE TABLE kyb_registry_extracts (
    id                 BIGSERIAL PRIMARY KEY,
    identifier_scheme  VARCHAR(16) NOT NULL,
    identifier_value   VARCHAR(64) NOT NULL,
    extract_json       TEXT NOT NULL,
    source             VARCHAR(32) NOT NULL,
    fetched_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_kyb_registry_extracts UNIQUE (identifier_scheme, identifier_value)
);

-- Transactional outbox (ADR-0003 / ADR-0050), same shape as the rest of the fleet.
CREATE TABLE kyb_outbox (
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

CREATE INDEX idx_kyb_outbox_status ON kyb_outbox (status, created_at);

-- Hibernate Reactive + PanacheEntity allocate ids from "<table>_seq" (allocationSize 50), which
-- BIGSERIAL does not create. Unquoted, lowercase, INCREMENT BY 50 — the repo convention after
-- party V19 / delegation V2.
CREATE SEQUENCE IF NOT EXISTS kyb_cases_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS kyb_registry_extracts_seq INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS kyb_outbox_seq INCREMENT BY 50;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
