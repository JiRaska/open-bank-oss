-- ADR-0220 D3.5 fraud-hold signal (issue #2749). Surrogate id + unique (party_id) business key,
-- same convention as engagement-service's party_adverse_state and party-service's
-- party_marketing_consent: an app-assigned natural-key PK is INSERT-only in Hibernate Reactive,
-- so a persist() on an already-active hold would 500 at flush without this.
-- Rollback: DROP TABLE fraud_hold; (see the note below before dropping once rows exist).
CREATE TABLE fraud_hold (
    id           UUID         NOT NULL PRIMARY KEY,
    party_id     UUID         NOT NULL,
    account_id   UUID         NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    reason       VARCHAR(64)  NOT NULL,
    rule_version VARCHAR(20)  NOT NULL,
    set_at       TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX ux_fraud_hold_party_id ON fraud_hold (party_id);
CREATE INDEX idx_fraud_hold_active_expires ON fraud_hold (active, expires_at);

-- Same shape as engagement_outbox/account_outbox (#1201): claimed_at supports the atomic
-- FOR UPDATE SKIP LOCKED claim query so two concurrently running dispatcher pods (an Argo
-- Rollouts canary window runs old and new simultaneously) can never publish the same row twice.
CREATE TABLE fraud_outbox (
    id            BIGSERIAL PRIMARY KEY,
    event_id      UUID NOT NULL UNIQUE,
    aggregate_id  UUID NOT NULL,
    event_type    VARCHAR(128) NOT NULL,
    payload       TEXT NOT NULL,
    status        VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    sent_at       TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at    TIMESTAMPTZ
);

CREATE INDEX idx_fraud_outbox_status_created_at ON fraud_outbox(status, created_at ASC);
CREATE INDEX idx_fraud_outbox_aggregate_id ON fraud_outbox(aggregate_id);

-- Hibernate Reactive + PanacheEntity allocate ids from a sequence named "<table>_seq"
-- (allocationSize 50) — BIGSERIAL above only creates "fraud_outbox_id_seq". Repo convention:
-- unquoted, lowercase, INCREMENT BY 50.
CREATE SEQUENCE IF NOT EXISTS fraud_outbox_seq INCREMENT BY 50;

-- Composite index backing FraudScoreRepository.countRecentByAccountAndVerdict — the existing
-- single-column idx_fraud_scores_account_id/idx_fraud_scores_verdict don't cover a query
-- filtering on all three (account_id, verdict, created_at) at once.
CREATE INDEX idx_fraud_scores_account_verdict_created
    ON fraud_scores (account_id, verdict, created_at);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;

-- Rollback note: DROP TABLE fraud_hold; DROP TABLE fraud_outbox; DROP SEQUENCE fraud_outbox_seq;
-- DROP INDEX idx_fraud_scores_account_verdict_created; Safe to roll back while inert (before this
-- feature's dispatch-enabled flag is flipped in gitops); once fraud_hold rows exist they document
-- a targeting-exclusion decision and should be archived, not dropped, same caveat as fraud_scores.
