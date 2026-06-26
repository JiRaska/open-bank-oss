-- ADR-0084 Phase 1: fraud scoring audit trail. Each POST /api/v1/fraud/score decision is persisted
-- as an immutable row — the reference fraud-rate dataset RTS Art. 18 needs and the per-verdict
-- evidence trail. Forward-only Flyway migration; rollback note below.
CREATE TABLE fraud_scores (
    id              BIGSERIAL PRIMARY KEY,
    score_id        UUID         NOT NULL UNIQUE,
    amount          NUMERIC(20,4) NOT NULL,
    currency        VARCHAR(3)   NOT NULL,
    rail            VARCHAR(40)  NOT NULL,
    account_id      UUID,
    counterparty_id UUID,
    verdict         VARCHAR(20)  NOT NULL,
    score           INTEGER      NOT NULL DEFAULT 0,
    reasons_json    TEXT         NOT NULL DEFAULT '[]',
    rule_version    VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fraud_scores_account_id ON fraud_scores(account_id);
CREATE INDEX idx_fraud_scores_verdict ON fraud_scores(verdict);
CREATE INDEX idx_fraud_scores_created_at ON fraud_scores(created_at);

-- Hibernate Reactive + PanacheEntity allocate ids from a sequence named "<table>_seq"
-- (allocationSize 50). The table uses BIGSERIAL (only "<table>_id_seq") and the schema is
-- generation:none, so without this every INSERT fails with relation "fraud_scores_seq" does not
-- exist. Repo convention: unquoted, lowercase, INCREMENT BY 50.
CREATE SEQUENCE IF NOT EXISTS fraud_scores_seq INCREMENT BY 50;

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;

-- Rollback note: DROP TABLE fraud_scores; DROP SEQUENCE fraud_scores_seq; (DROP TABLE drops the
-- indexes with it). Safe to roll back while the
-- service is inert (Phase 1 — no payment surface writes to it yet); once rows exist they are an
-- audit/regulatory dataset and must be archived, not dropped.
