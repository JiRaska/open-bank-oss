-- Ephemeral recipient authorization is separate from immutable disclosure evidence.
-- Rollback: stop issue/verify/content traffic first. Before production use and only if empty,
-- drop the two effect-ledger tables, then disclosure_redemptions. After use, retain access evidence
-- and erase secret hashes only
-- through the governed retention process; reverting the application must not delete audit history.
CREATE TABLE disclosure_redemptions (
    id UUID PRIMARY KEY,
    disclosure_id UUID NOT NULL REFERENCES delegation_disclosures(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ISSUED', 'VERIFIED', 'LOCKED', 'REVOKED', 'EXHAUSTED')),
    recipient_hint VARCHAR(160) NOT NULL,
    magic_token_hash VARCHAR(64) UNIQUE CHECK (
        magic_token_hash IS NULL OR magic_token_hash ~ '^[0-9a-f]{64}$'
    ),
    otp_salt VARCHAR(32) CHECK (otp_salt IS NULL OR otp_salt ~ '^[0-9a-f]{32}$'),
    otp_hash VARCHAR(64) CHECK (otp_hash IS NULL OR otp_hash ~ '^[0-9a-f]{64}$'),
    access_ticket_hash VARCHAR(64) UNIQUE CHECK (
        access_ticket_hash IS NULL OR access_ticket_hash ~ '^[0-9a-f]{64}$'
    ),
    issuance_idempotency_key_hash VARCHAR(64) NOT NULL CHECK (
        issuance_idempotency_key_hash ~ '^[0-9a-f]{64}$'
    ),
    verification_idempotency_key_hash VARCHAR(64) CHECK (
        verification_idempotency_key_hash IS NULL OR verification_idempotency_key_hash ~ '^[0-9a-f]{64}$'
    ),
    expires_at TIMESTAMPTZ NOT NULL,
    max_views SMALLINT NOT NULL CHECK (max_views BETWEEN 1 AND 10),
    views SMALLINT NOT NULL DEFAULT 0 CHECK (views BETWEEN 0 AND max_views),
    failed_attempts SMALLINT NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    verified_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (status NOT IN ('VERIFIED', 'EXHAUSTED') OR verified_at IS NOT NULL),
    CHECK (status <> 'ISSUED' OR (
        verified_at IS NULL AND access_ticket_hash IS NULL AND
        magic_token_hash IS NOT NULL AND otp_salt IS NOT NULL AND otp_hash IS NOT NULL
    )),
    CHECK (status <> 'VERIFIED' OR access_ticket_hash IS NOT NULL),
    CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
    CHECK (access_ticket_hash IS NULL OR verified_at IS NOT NULL)
);

CREATE TABLE disclosure_redemption_view_consumptions (
    redemption_id UUID NOT NULL REFERENCES disclosure_redemptions(id),
    idempotency_key_hash VARCHAR(64) NOT NULL CHECK (idempotency_key_hash ~ '^[0-9a-f]{64}$'),
    view_number SMALLINT NOT NULL CHECK (view_number BETWEEN 1 AND 10),
    consumed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (redemption_id, idempotency_key_hash),
    UNIQUE (redemption_id, view_number)
);

CREATE TABLE disclosure_redemption_verification_attempts (
    redemption_id UUID NOT NULL REFERENCES disclosure_redemptions(id),
    idempotency_key_hash VARCHAR(64) NOT NULL CHECK (idempotency_key_hash ~ '^[0-9a-f]{64}$'),
    successful BOOLEAN NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (redemption_id, idempotency_key_hash)
);

CREATE INDEX idx_disclosure_redemptions_expiry ON disclosure_redemptions(expires_at)
    WHERE status IN ('ISSUED', 'VERIFIED');

CREATE UNIQUE INDEX uq_disclosure_redemptions_active
    ON disclosure_redemptions(disclosure_id)
    WHERE status IN ('ISSUED', 'VERIFIED');
