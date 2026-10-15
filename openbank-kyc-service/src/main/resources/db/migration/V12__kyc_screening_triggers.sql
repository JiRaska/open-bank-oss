-- ADR-0256 slice 1: durable trigger loop SANCTIONS_LIST_CHANGED -> screening case.
--
-- One row per (publication, party): the sanctions-service change journal publishes a
-- SANCTIONS_LIST_CHANGED event per 64-entry chunk of a publication; the per-party trigger
-- dedupes at PUBLICATION granularity so a large diff spanning many chunks still opens at
-- most one case per party. State machine:
--   PENDING      — candidate recorded, coordinator case not yet opened
--   OPENED       — coordinator accepted (201/409); coordinator_case_id recorded
--   OPEN_FAILED  — open did not succeed; next_attempt_at backs off the retry job,
--                  and the KYC_COORDINATOR_OPEN_FAILED outbox row (written in the same
--                  transaction as the state flip) is the operator-visible failure record.
-- `diff` is TEXT, not JSONB: under Hibernate Reactive the Vert.x PG client returns a JsonArray
-- for jsonb columns, which cannot be cast to String — every read would throw
-- ClassCastException (measured in openbank-campaign-service V2 / openbank-copilot-service).
-- The PK is BIGSERIAL, but PanacheEntity allocates ids from a sequence named
-- "<table>_seq" (allocationSize 50) — without it every INSERT fails with
-- relation "kyc_screening_triggers_seq" does not exist (V4 pattern).
-- Rollback (before apply only — never edit an applied migration):
--   DROP TABLE IF EXISTS kyc_screening_triggers;
--   DROP SEQUENCE IF EXISTS kyc_screening_triggers_seq;

CREATE SEQUENCE IF NOT EXISTS kyc_screening_triggers_seq INCREMENT BY 50;

CREATE TABLE kyc_screening_triggers (
    id            BIGSERIAL PRIMARY KEY,
    trigger_id    UUID        NOT NULL UNIQUE,
    party_id      UUID        NOT NULL,
    trigger_type  VARCHAR(50) NOT NULL,
    publication_id UUID       NOT NULL,
    chunk_index   BIGINT,
    list_type     VARCHAR(50),
    diff          TEXT        NOT NULL,
    state         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    coordinator_case_id VARCHAR(64),
    attempts      INTEGER     NOT NULL DEFAULT 0,
    failed_reason VARCHAR(50),
    next_attempt_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at   TIMESTAMPTZ
);

-- At most one trigger per (publication, party): Kafka at-least-once redelivery and the
-- multi-chunk fan-out both collapse onto this key.
CREATE UNIQUE INDEX uq_kyc_screening_triggers_pub_party
    ON kyc_screening_triggers (publication_id, party_id);
-- The retry job's claim: failed rows whose backoff has elapsed, oldest first.
CREATE INDEX ix_kyc_screening_triggers_retry
    ON kyc_screening_triggers (state, next_attempt_at, id);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
