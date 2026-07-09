-- ADR-0084 §3 v4: per-(account, payee) payment history for the "new-payee + high-amount"
-- combination rule (deferred from the original v3 rollout — no payee-history signal existed then).
-- One row per (account_id, payee_identifier) — upserted on every transaction signal from the same
-- Kafka path that updates velocity_aggregates. payee_identifier is the counterparty account UUID
-- (as text) from openbank.transactions.transaction.initiated's targetAccountId; a text column keeps
-- the door open for a non-account payee identifier (e.g. IBAN/BIC for external rails) without a
-- future schema change.
--
-- Idempotent replay: last_transaction_id records the aggregateId of the last signal that was
-- actually counted. The upsert's DO UPDATE ... WHERE guard skips the increment when the incoming
-- aggregateId matches the row already recorded, so a redelivered/duplicate Kafka message does not
-- double-count payment_count. (This is stronger than the pre-existing velocity_aggregates upsert,
-- which has no such guard and double-counts on redelivery — a known gap, not extended here.)
--
-- Rollback note: DROP TABLE payee_history; (no sequences — PK is composite, no BIGSERIAL)
CREATE TABLE payee_history (
    account_id          UUID          NOT NULL,
    payee_identifier    VARCHAR(64)   NOT NULL,
    first_seen_at       TIMESTAMPTZ   NOT NULL,
    last_paid_at        TIMESTAMPTZ   NOT NULL,
    payment_count       BIGINT        NOT NULL DEFAULT 0,
    last_transaction_id UUID,
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, payee_identifier)
);

CREATE INDEX idx_payee_history_account ON payee_history(account_id);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
