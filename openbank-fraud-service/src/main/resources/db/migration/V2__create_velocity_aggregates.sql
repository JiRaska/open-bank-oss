-- ADR-0084 Phase 2: per-account rolling velocity aggregates for fraud signal plane.
-- One row per (account_id, velocity_window, currency, window_start) — upserted on every transaction
-- signal. Currency is in the PK so multi-currency accounts keep separate buckets: CZK and EUR
-- transactions for the same account accumulate independently, preventing analytically incorrect
-- cross-currency sums. Note: column named velocity_window (not window) — PostgreSQL reserved word.
-- Rollback note: DROP TABLE velocity_aggregates; (no sequences — PK is composite, no BIGSERIAL)
CREATE TABLE velocity_aggregates (
    account_id        UUID          NOT NULL,
    velocity_window   VARCHAR(4)    NOT NULL,  -- H1 / H24 / D7
    currency          VARCHAR(3)    NOT NULL,
    window_start      TIMESTAMPTZ   NOT NULL,
    transaction_count BIGINT        NOT NULL DEFAULT 0,
    total_amount      NUMERIC(20,4) NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, velocity_window, currency, window_start)
);

CREATE INDEX idx_vel_account_window ON velocity_aggregates(account_id, velocity_window);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
