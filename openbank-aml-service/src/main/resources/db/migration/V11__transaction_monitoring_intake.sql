-- SPDX-License-Identifier: Apache-2.0
-- ADR-0371 slice 1: intake and per-party counters for post-booking transaction monitoring.
--
-- Forward-only, additive: four new tables, no change to an existing one.
-- Rollback: DROP TABLE aml_movement_counter; DROP TABLE aml_account_party;
--           DROP TABLE aml_movement_origin; DROP TABLE aml_booked_movement;
-- (nothing else references them; dropping loses monitoring history, which is retained data).

-- One row per booked change of one customer account in one currency. The primary key is the
-- ledger's business key, so a redelivered or re-emitted booking is a no-op.
CREATE TABLE aml_booked_movement (
    journal_entry_id UUID          NOT NULL,
    account_id       UUID          NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    direction        VARCHAR(3)    NOT NULL,
    amount           NUMERIC(19,4) NOT NULL,
    transaction_id   UUID          NOT NULL,
    entry_date       DATE          NOT NULL,
    booked_at        TIMESTAMPTZ   NOT NULL,
    source_event_id  UUID,
    recorded_at      TIMESTAMPTZ   NOT NULL,
    -- Set together, in the transaction that increments the counters.
    party_id         UUID,
    rail             VARCHAR(32),
    counted_at       TIMESTAMPTZ,
    last_attempt_at  TIMESTAMPTZ,
    PRIMARY KEY (journal_entry_id, account_id, currency),
    CONSTRAINT ck_aml_booked_movement_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_aml_booked_movement_amount CHECK (amount > 0),
    CONSTRAINT ck_aml_booked_movement_counted
        CHECK ((counted_at IS NULL AND party_id IS NULL AND rail IS NULL)
            OR (counted_at IS NOT NULL AND party_id IS NOT NULL AND rail IS NOT NULL))
);
CREATE INDEX idx_aml_booked_movement_uncounted
    ON aml_booked_movement (last_attempt_at NULLS FIRST, recorded_at) WHERE counted_at IS NULL;
CREATE INDEX idx_aml_booked_movement_party ON aml_booked_movement (party_id, booked_at);
CREATE INDEX idx_aml_booked_movement_transaction ON aml_booked_movement (transaction_id);

-- Rail of the originating transaction, from the originating domain event. First write wins.
CREATE TABLE aml_movement_origin (
    transaction_id UUID        PRIMARY KEY,
    rail           VARCHAR(32) NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL
);

-- account -> owning party, as resolved from the account authority.
CREATE TABLE aml_account_party (
    account_id  UUID        PRIMARY KEY,
    party_id    UUID        NOT NULL,
    resolved_at TIMESTAMPTZ NOT NULL
);

-- Rolling counters. Currency is part of the key: a count never spans currencies and no amount is
-- converted. scheme_revision is part of the key: a changed band starts new counters.
CREATE TABLE aml_movement_counter (
    party_id        UUID        NOT NULL,
    currency        VARCHAR(3)  NOT NULL,
    direction       VARCHAR(3)  NOT NULL,
    rail            VARCHAR(32) NOT NULL,
    scheme_revision INTEGER     NOT NULL,
    bucket_start    TIMESTAMPTZ NOT NULL,
    band_code       VARCHAR(64) NOT NULL,
    bucket_seconds  INTEGER     NOT NULL,
    movement_count  BIGINT      NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (party_id, currency, direction, rail, scheme_revision, bucket_start, band_code),
    CONSTRAINT ck_aml_movement_counter_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_aml_movement_counter_count CHECK (movement_count > 0)
);
CREATE INDEX idx_aml_movement_counter_bucket ON aml_movement_counter (bucket_start);
