-- SPDX-License-Identifier: Apache-2.0
-- ADR-0282 D2/D4/D5 — the Lístek ledger, benefit grants and this service's outbox.
--
-- Rollback (reverse order, no dependents outside this service):
--   DROP SEQUENCE loyalty_outbox_seq; DROP TABLE loyalty_outbox;   (V2 first, see below)
--   DROP TABLE benefit_grant; DROP TABLE leaf_ledger_entry;
--
-- No extension is created here. Everything below is core Postgres, so the untrusted-extension
-- split (a migration that works as superuser locally and fails as the CNPG-issued owner role in
-- the cluster) cannot apply to this service.

-- The append-only ledger. `remaining_leaves` is the ONLY column ever updated in place: it is the
-- FIFO consumption pointer on an EARN lot, so a burn debits the oldest lots while the original
-- award amount in `leaves` stays intact for audit.
CREATE TABLE leaf_ledger_entry (
    id                   UUID         NOT NULL PRIMARY KEY,
    party_id             UUID         NOT NULL,
    entry_type           VARCHAR(16)  NOT NULL,
    leaves               INTEGER      NOT NULL CHECK (leaves > 0),
    remaining_leaves     INTEGER      NOT NULL DEFAULT 0 CHECK (remaining_leaves >= 0),
    earn_source_id       VARCHAR(64),
    benefit_id           VARCHAR(64),
    rule_version         VARCHAR(16)  NOT NULL,
    correlation_event_id UUID         NOT NULL,
    occurred_at          TIMESTAMPTZ  NOT NULL,
    expires_at           TIMESTAMPTZ,
    CONSTRAINT leaf_remaining_within_award CHECK (remaining_leaves <= leaves),
    -- An EARN lot must carry both its source and its expiry; the domain enforces this too, and
    -- the constraint is what holds against a hand-written INSERT during an incident.
    CONSTRAINT leaf_earn_is_complete CHECK (
        entry_type <> 'EARN' OR (earn_source_id IS NOT NULL AND expires_at IS NOT NULL)
    ),
    CONSTRAINT leaf_burn_names_benefit CHECK (entry_type <> 'BURN' OR benefit_id IS NOT NULL)
);

-- The earn idempotency guard, keyed on the ACHIEVEMENT: (party, source, triggering event). A
-- redelivered domain event carries the same correlation id, so the second attempt hits this index
-- instead of awarding again. Partial, because only EARN rows have an earn_source_id.
CREATE UNIQUE INDEX uq_leaf_earn_idempotency
    ON leaf_ledger_entry (party_id, earn_source_id, correlation_event_id)
    WHERE entry_type = 'EARN';

CREATE INDEX idx_leaf_ledger_party_occurred ON leaf_ledger_entry (party_id, occurred_at DESC);

-- The expiry sweep's driving index: lots that still hold value and have a due date.
CREATE INDEX idx_leaf_ledger_expiry
    ON leaf_ledger_entry (expires_at)
    WHERE entry_type = 'EARN' AND remaining_leaves > 0;

CREATE TABLE benefit_grant (
    id              UUID         NOT NULL PRIMARY KEY,
    party_id        UUID         NOT NULL,
    benefit_id      VARCHAR(64)  NOT NULL,
    price_leaves    INTEGER      NOT NULL CHECK (price_leaves > 0),
    status          VARCHAR(16)  NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    reserved_at     TIMESTAMPTZ  NOT NULL,
    granted_at      TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    -- The real idempotency guard. The application's read-before-write is the fast path; this
    -- constraint is what holds when two retries race and neither read sees the other.
    CONSTRAINT uq_benefit_grant_idempotency UNIQUE (party_id, idempotency_key),
    CONSTRAINT benefit_granted_has_timestamp CHECK (status <> 'GRANTED' OR granted_at IS NOT NULL)
);

CREATE INDEX idx_benefit_grant_party ON benefit_grant (party_id, reserved_at DESC);

-- Same shape as account_outbox (#1201): claimed_at supports the atomic FOR UPDATE SKIP LOCKED
-- claim, so two pods cannot dispatch one row twice.
CREATE TABLE loyalty_outbox (
    id            BIGSERIAL PRIMARY KEY,
    event_id      UUID NOT NULL UNIQUE,
    aggregate_id  UUID NOT NULL,
    event_type    VARCHAR(128) NOT NULL,
    payload       TEXT NOT NULL,
    status        VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    sent_at       TIMESTAMPTZ,
    last_error    TEXT,
    claimed_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loyalty_outbox_status_created_at ON loyalty_outbox (status, created_at ASC);
CREATE INDEX idx_loyalty_outbox_aggregate_id ON loyalty_outbox (aggregate_id);

-- PanacheEntity's id generator is a POOLED sequence named "<table>_seq" (allocationSize 50).
-- BIGSERIAL above only creates "loyalty_outbox_id_seq", so every INSERT via persist() would fail
-- at runtime with relation "loyalty_outbox_seq" does not exist. Same fleet convention: unquoted,
-- lowercase, INCREMENT BY 50.
CREATE SEQUENCE IF NOT EXISTS loyalty_outbox_seq INCREMENT BY 50;
