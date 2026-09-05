-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors.
-- ADR-0284 D6: the business half of the onboarding read model. A SEPARATE table from
-- onboarding_records on purpose — that one is keyed per PARTY and counts people, this one is keyed
-- per CASE, exists before any entity party does, and carries several humans. Folding them together
-- would make every existing cockpit count ambiguous.
--
-- last_event_at is the projection's ordering guard: an event older than the row it would overwrite
-- is ignored, so a replay cannot walk a live customer backwards into AWAITING_SIGNATURES.
--
-- Rollback:
--   DROP TABLE business_onboarding_records;
--   DROP SEQUENCE IF EXISTS business_onboarding_records_seq;

CREATE TABLE business_onboarding_records (
    id                  BIGSERIAL PRIMARY KEY,
    case_id             UUID        NOT NULL UNIQUE,
    identifier_scheme   TEXT        NOT NULL,
    identifier          TEXT        NOT NULL,
    country             CHAR(2),
    legal_name          TEXT,
    legal_form_class    TEXT,
    initiator_party_id  UUID        NOT NULL,
    entity_party_id     UUID,
    case_status         TEXT        NOT NULL,
    stage               TEXT        NOT NULL,
    required_signatures INTEGER,
    signed_count        INTEGER     NOT NULL DEFAULT 0,
    review_reason       TEXT,
    last_event_at       TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_business_onboarding_stage ON business_onboarding_records (stage, updated_at DESC);
CREATE INDEX idx_business_onboarding_initiator ON business_onboarding_records (initiator_party_id);
CREATE INDEX idx_business_onboarding_entity ON business_onboarding_records (entity_party_id)
    WHERE entity_party_id IS NOT NULL;

-- Hibernate Reactive + PanacheEntity allocate ids from "<table>_seq" (allocationSize 50), which
-- BIGSERIAL does not create. Unquoted, lowercase, INCREMENT BY 50 — the convention party V19 and
-- delegation V2 restored after both got it wrong.
CREATE SEQUENCE IF NOT EXISTS business_onboarding_records_seq INCREMENT BY 50;
