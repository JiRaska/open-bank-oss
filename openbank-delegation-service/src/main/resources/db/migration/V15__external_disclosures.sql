-- ADR-0232 D7b: external disclosure is sealed document emission, not a live API grant.
-- The link secret and OTP are intentionally represented only by domain-separated SHA-256 hashes.

CREATE TABLE delegation_external_disclosures (
    id                UUID PRIMARY KEY,
    delegation_id     UUID NOT NULL REFERENCES delegation_grants(id) ON DELETE RESTRICT,
    document_id       UUID NOT NULL,
    recipient_label   VARCHAR(256) NOT NULL,
    link_secret_hash  CHAR(64) NOT NULL,
    otp_hash          CHAR(64) NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    max_views         INT NOT NULL,
    verified_at       TIMESTAMPTZ,
    view_count        INT NOT NULL DEFAULT 0,
    revoked_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_external_disclosure_max_views CHECK (max_views >= 1),
    CONSTRAINT chk_external_disclosure_view_count CHECK (view_count >= 0 AND view_count <= max_views),
    CONSTRAINT chk_external_disclosure_expiry CHECK (expires_at > created_at)
);

-- Both the issuer's transparency list and the public opaque-secret lookup need bounded indexes.
CREATE INDEX idx_external_disclosure_delegation ON delegation_external_disclosures(delegation_id, created_at DESC);
CREATE UNIQUE INDEX uq_external_disclosure_link_secret_hash ON delegation_external_disclosures(link_secret_hash);

-- Retain one immutable time record per successful sealed-document release. Keeping this separate
-- from the counter lets the grantor see when the recipient used the disclosure without ever
-- retaining a browser identifier, IP address, or the recipient's raw link secret.
CREATE TABLE delegation_external_disclosure_views (
    id            BIGSERIAL PRIMARY KEY,
    disclosure_id UUID NOT NULL REFERENCES delegation_external_disclosures(id) ON DELETE RESTRICT,
    viewed_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_external_disclosure_views_disclosure
    ON delegation_external_disclosure_views(disclosure_id, viewed_at ASC);
