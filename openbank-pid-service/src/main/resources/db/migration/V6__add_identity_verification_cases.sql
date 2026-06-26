-- SPDX-License-Identifier: MPL-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
--
-- ADR-0072 §1 / ADR-0030 four-eyes — Identity verification cases (PR4 of the delivery order).
--
-- When pid /resolve cannot auto-decide (an RČ blind-index collision with divergent
-- attributes, or a no-RČ namesake candidate) it now opens a DURABLE four-eyes case here
-- instead of silently returning a neutral pending state with caseId=null. Two DISTINCT
-- approvers must concur on a verdict before the case is DECIDED (ADR-0030).
--
-- A DECIDED case is consulted by subsequent /resolve calls (adjudication cache), so a
-- human decision deterministically steers the next attempt for the same applicant:
--   LINK_TO_EXISTING -> MatchExisting(party)   DISTINCT_NEW -> NoMatch   REJECT -> stays blocked.
--
-- Privacy (ADR-0072): the plaintext RČ is NEVER stored. `blind_index` is the keyed
-- HMAC-SHA256 hex (same value already held in party_external_ids); the applicant name and
-- birthdate are the applicant's own attributes, required for the operator to adjudicate.

CREATE TABLE identity_verification_case (
    id                      UUID PRIMARY KEY,
    dedup_key               TEXT        NOT NULL,
    trigger                 TEXT        NOT NULL,
    status                  TEXT        NOT NULL,
    applicant_given_name    TEXT        NOT NULL,
    applicant_family_name   TEXT        NOT NULL,
    applicant_birthdate     DATE        NOT NULL,
    applicant_birthplace    TEXT,
    applicant_nationalities TEXT        NOT NULL DEFAULT '',
    blind_index             TEXT,
    candidate_party_ids     TEXT        NOT NULL DEFAULT '',
    first_approver          TEXT,
    first_verdict           TEXT,
    first_link_party_id     UUID,
    first_notes             TEXT,
    first_at                TIMESTAMPTZ,
    second_approver         TEXT,
    second_at               TIMESTAMPTZ,
    final_verdict           TEXT,
    final_link_party_id     UUID,
    decided_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_ivc_trigger CHECK (trigger IN ('RN_COLLISION', 'NAMESAKE_CANDIDATE')),
    CONSTRAINT chk_ivc_status  CHECK (status IN ('OPEN', 'AWAITING_SECOND_APPROVAL', 'DECIDED')),
    CONSTRAINT chk_ivc_first_verdict
        CHECK (first_verdict IS NULL OR first_verdict IN ('LINK_TO_EXISTING', 'DISTINCT_NEW', 'REJECT')),
    CONSTRAINT chk_ivc_final_verdict
        CHECK (final_verdict IS NULL OR final_verdict IN ('LINK_TO_EXISTING', 'DISTINCT_NEW', 'REJECT'))
);

-- At most one ACTIVE (not DECIDED) case per dedup_key: retries reuse the open case
-- instead of piling up duplicates.
CREATE UNIQUE INDEX uq_ivc_active_dedup
    ON identity_verification_case (dedup_key)
    WHERE status <> 'DECIDED';

-- Adjudication-cache lookup: the most recent DECIDED case for a dedup_key.
CREATE INDEX idx_ivc_decided_dedup
    ON identity_verification_case (dedup_key, decided_at)
    WHERE status = 'DECIDED';

-- Cockpit queue: list OPEN / AWAITING cases newest-first.
CREATE INDEX idx_ivc_status_created
    ON identity_verification_case (status, created_at);
