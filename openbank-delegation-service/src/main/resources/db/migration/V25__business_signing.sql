-- ADR-0312: business payment signing — entity signing policy, signer groups, trusted payees and
-- N-of-M approval requests (#10281 item 2).
--
-- Additive only: four new tables, nothing existing is altered. An entity with no row in
-- signing_policies uses the policy DERIVED from its live party-service mandates, so the absence of
-- data here is the default state, never an error.
--
-- Rollback: deploy the previous image first — it never reads these tables, so they can stay in
-- place. If they must go, write a NEW forward migration dropping them in the reverse order
-- (approval_signatures, approval_requests, trusted_payees, signer_groups, signing_policies);
-- never edit this applied file (Flyway checksums it). Pending approval requests are lost on drop,
-- which is safe: a held payment never reached a rail.

CREATE TABLE signing_policies (
    entity_party_id            UUID          PRIMARY KEY,
    version                    INTEGER       NOT NULL CHECK (version >= 1),
    -- Canonical JSON array of rules; parsed and validated by the application on every read.
    rules_json                 TEXT          NOT NULL,
    trusted_payee_cap_amount   NUMERIC(19,4),
    trusted_payee_cap_currency VARCHAR(3),
    updated_at                 TIMESTAMPTZ   NOT NULL,
    updated_by_approval_id     UUID,
    CONSTRAINT chk_signing_policy_cap_pair CHECK (
        (trusted_payee_cap_amount IS NULL) = (trusted_payee_cap_currency IS NULL)
    )
);

CREATE TABLE signer_groups (
    row_id              UUID          PRIMARY KEY,
    id                  VARCHAR(64)   NOT NULL,
    entity_party_id     UUID          NOT NULL,
    name                VARCHAR(120)  NOT NULL,
    member_party_ids    TEXT          NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    updated_by_approval_id UUID       NOT NULL,
    CONSTRAINT uq_signer_group_entity_id UNIQUE (entity_party_id, id)
);

CREATE TABLE trusted_payees (
    id                      UUID          PRIMARY KEY,
    entity_party_id         UUID          NOT NULL,
    iban                    VARCHAR(34)   NOT NULL,
    name                    VARCHAR(140)  NOT NULL,
    bic                     VARCHAR(11),
    status                  VARCHAR(16)   NOT NULL CHECK (status IN ('ACTIVE', 'REMOVED')),
    added_at                TIMESTAMPTZ   NOT NULL,
    added_by_approval_id    UUID          NOT NULL,
    removed_at              TIMESTAMPTZ,
    removed_by_approval_id  UUID
);

-- One ACTIVE row per (entity, normalised IBAN): the evaluation lookup, and a second barrier
-- against two concurrently approved PAYEE_ADD requests creating duplicate trust.
CREATE UNIQUE INDEX uq_trusted_payee_active_iban
    ON trusted_payees (entity_party_id, iban) WHERE status = 'ACTIVE';

CREATE TABLE approval_requests (
    id                        UUID          PRIMARY KEY,
    entity_party_id           UUID          NOT NULL,
    kind                      VARCHAR(16)   NOT NULL
        CHECK (kind IN ('PAYMENT', 'PAYEE_ADD', 'PAYEE_REMOVE', 'POLICY_CHANGE')),
    payload                   TEXT          NOT NULL,
    payload_sha256            CHAR(64)      NOT NULL,
    summary_json              TEXT,
    policy_version            INTEGER       NOT NULL CHECK (policy_version >= 0),
    required_signatures       INTEGER       NOT NULL CHECK (required_signatures >= 1),
    eligible_signer_ids       TEXT          NOT NULL,
    must_include_group_id     VARCHAR(64),
    must_include_signer_ids   TEXT,
    initiator_party_id        UUID          NOT NULL,
    -- Display-name snapshots taken at creation, for the signer notification copy only; never
    -- used for a decision. NULL when party-service/pid-service could not name them.
    entity_name               VARCHAR(200),
    initiator_name            VARCHAR(200),
    status                    VARCHAR(24)   NOT NULL
        CHECK (status IN ('AWAITING_INITIATOR', 'PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'RELEASED', 'RELEASE_FAILED')),
    rejected_by_party_id      UUID,
    rejection_reason          VARCHAR(500),
    rejected_at               TIMESTAMPTZ,
    expires_at                TIMESTAMPTZ   NOT NULL,
    created_at                TIMESTAMPTZ   NOT NULL,
    updated_at                TIMESTAMPTZ   NOT NULL,
    claim_token               UUID,
    released_at               TIMESTAMPTZ,
    release_ref               VARCHAR(200),
    release_error             VARCHAR(500),
    -- Only a payment is released; a signed administrative change is applied on approval.
    CONSTRAINT chk_approval_release_only_payment CHECK (
        kind = 'PAYMENT' OR status NOT IN ('RELEASED', 'RELEASE_FAILED')
    ),
    -- A RELEASED/RELEASE_FAILED row always carries the token of the one claim that won.
    CONSTRAINT chk_approval_released_has_claim CHECK (
        status NOT IN ('RELEASED', 'RELEASE_FAILED') OR claim_token IS NOT NULL
    )
);

CREATE INDEX idx_approval_requests_entity_status ON approval_requests (entity_party_id, status, created_at DESC);
CREATE INDEX idx_approval_requests_expiry ON approval_requests (expires_at) WHERE status IN ('AWAITING_INITIATOR', 'PENDING', 'APPROVED');

CREATE TABLE approval_signatures (
    id                   UUID          PRIMARY KEY,
    approval_request_id  UUID          NOT NULL REFERENCES approval_requests (id),
    party_id             UUID          NOT NULL,
    sca_challenge_id     UUID          NOT NULL,
    signed_at            TIMESTAMPTZ   NOT NULL,
    -- The same person counts once (ADR-0312), enforced here as well as in the domain.
    CONSTRAINT uq_approval_signature_party UNIQUE (approval_request_id, party_id),
    -- One SCA ceremony authorises one signature, ever.
    CONSTRAINT uq_approval_signature_challenge UNIQUE (sca_challenge_id)
);
