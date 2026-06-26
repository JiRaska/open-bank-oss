-- ADR-0039 Phase D: balance-service as a projection of the ledger.
-- The balance projection consumes AccountBookedChanged events (Kafka, at-least-once) and applies
-- each signed booked delta exactly once. This table is the idempotency/dedup ledger: one row per
-- applied event, keyed by the ledger journal entry plus the (account, currency) it moved. The
-- consumer records the row in the SAME transaction as the balance update, so a redelivery that
-- finds the row already present is skipped — the booked balance can never be double-applied.
--
-- Rollback note:
--   DROP TABLE ledger_projection_event;
-- (Reversible: a pure dedup/audit table with no FK to or from balances/holds. Dropping it only
--  loses the applied-event history; with the projection consumer disabled no money-path state is
--  affected. NB: re-enabling the consumer after a drop with auto.offset.reset=earliest could
--  re-apply historical events — re-create this table before re-enabling.)

CREATE TABLE ledger_projection_event (
    journal_entry_id UUID        NOT NULL,
    account_id       UUID        NOT NULL,
    currency         VARCHAR(3)  NOT NULL,
    delta            NUMERIC(19,4) NOT NULL,
    transaction_id   UUID        NOT NULL,
    entry_date       DATE        NOT NULL,
    applied_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (journal_entry_id, account_id, currency)
);

CREATE INDEX idx_ledger_projection_event_tx ON ledger_projection_event(transaction_id);
