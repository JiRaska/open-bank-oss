-- SPDX-License-Identifier: MPL-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
--
-- ADR-0094 go-live — durable persistence for the EUDI issuer/verifier stores. Before this, the
-- Token Status List (revocation), the status-index allocation counter, and the in-flight
-- OpenID4VCI/OpenID4VP exchanges lived only in heap: a pod restart silently un-revoked every
-- previously-revoked credential AND reset the index counter so new credentials reused old status
-- indices — a fail-OPEN revocation mechanism, which is a hard eIDAS 2.0 go-live blocker. These
-- three tables (+ one allocation sequence) move that state into the backed-up CNPG database, making
-- it survive restarts and safe across replicas. Selected at build time by
-- `openbank.pid.eudi.persistence=postgres` (the default); the in-memory path remains for fast tests.
--
-- Rollback:
--   DROP TABLE eudi_presentation_exchange;
--   DROP TABLE eudi_credential_offer;
--   DROP TABLE eudi_status_list_entry;
--   DROP SEQUENCE eudi_status_list_idx_seq;

-- ── Token Status List (revocation, eIDAS 2.0) ─────────────────────────────────
-- One row per issued credential's status index. The sequence is the durable, multi-replica-safe
-- replacement for the in-memory `nextIndex` counter; `revoked` replaces the in-memory bitset. The
-- row's existence is the "this index was allocated" record (so revoking an unknown index is rejected).
CREATE SEQUENCE IF NOT EXISTS eudi_status_list_idx_seq INCREMENT BY 1;

CREATE TABLE eudi_status_list_entry (
    idx          BIGINT      PRIMARY KEY,
    revoked      BOOLEAN     NOT NULL DEFAULT FALSE,
    allocated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMPTZ
);

-- Partial index: building a status-list token reads ONLY the revoked rows (the common case is "few
-- revoked out of many issued"), so keep that scan tight.
CREATE INDEX idx_eudi_status_list_revoked ON eudi_status_list_entry (idx) WHERE revoked;

-- ── In-flight OpenID4VCI pre-authorized-code offers ───────────────────────────
-- Short-lived (TTL); persisted for multi-replica safety (the token endpoint and the credential
-- endpoint may land on different replicas). Holds the verified offered claims as JSON; no secret
-- material beyond the single-use pre-auth code / access token, both single-use by construction.
CREATE TABLE eudi_credential_offer (
    pre_auth_code TEXT        PRIMARY KEY,
    access_token  TEXT        UNIQUE,
    status        TEXT        NOT NULL,
    claims_json   TEXT        NOT NULL,
    c_nonce       TEXT,
    created_at    TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_eudi_credential_offer_expires_at ON eudi_credential_offer (expires_at);

-- ── In-flight OpenID4VP presentation exchanges ────────────────────────────────
-- Short-lived (TTL); persisted for multi-replica safety (create / wallet direct_post callback /
-- poll may land on different replicas). The single-use nonce is spent by an atomic conditional
-- UPDATE (PENDING -> COMPLETED), which is the anti-replay guarantee. `result_json` carries the
-- resolved decision (PidClaims + the resolution verdict) for the poll endpoint.
CREATE TABLE eudi_presentation_exchange (
    transaction_id TEXT        PRIMARY KEY,
    nonce          TEXT        NOT NULL,
    audience       TEXT        NOT NULL,
    status         TEXT        NOT NULL,
    result_json    TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_eudi_presentation_exchange_expires_at ON eudi_presentation_exchange (expires_at);
