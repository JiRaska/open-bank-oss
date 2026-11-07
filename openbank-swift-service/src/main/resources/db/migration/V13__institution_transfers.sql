-- Bank-own (financial-institution) credit transfers released by treasury (contract 9.3).
-- Natural-key idempotency: payment_id and idempotency_key are each UNIQUE, so a replayed release
-- can only ever resolve to the row that already exists, never to a second message.
-- swift_institution_transfer_claims arbitrates which outcome owns a payment;
-- swift_institution_transfer_rejections records a release refused by validation, once per payment
-- id, so its REJECTED status event is emitted exactly once however often the record is replayed.
-- Rollback: before this migration is applied anywhere, drop both tables; once applied, a
-- follow-up migration drops them (never edit an applied Flyway migration — its checksum is pinned).
CREATE TABLE swift_institution_transfers (
    id                          UUID PRIMARY KEY,
    payment_id                  UUID NOT NULL UNIQUE,
    idempotency_key             VARCHAR(255) NOT NULL UNIQUE,
    rail                        VARCHAR(10) NOT NULL,
    currency                    CHAR(3) NOT NULL,
    amount_minor_units          BIGINT NOT NULL CHECK (amount_minor_units > 0),
    value_date                  DATE NOT NULL,
    debtor_bic                  VARCHAR(11) NOT NULL,
    debtor_account              VARCHAR(34) NOT NULL,
    debtor_gl_accounting_class  VARCHAR(64) NOT NULL,
    creditor_bic                VARCHAR(11) NOT NULL,
    creditor_account            VARCHAR(34) NOT NULL,
    creditor_name               VARCHAR(140) NOT NULL,
    intermediary_bic            VARCHAR(11),
    remittance_information      VARCHAR(140),
    deal_ids                    TEXT NOT NULL,
    flow_ids                    TEXT NOT NULL,
    released_by                 VARCHAR(255) NOT NULL,
    released_at                 TIMESTAMPTZ NOT NULL,
    synthetic                   BOOLEAN NOT NULL,
    ssi_version                 BIGINT CHECK (ssi_version IS NULL OR ssi_version > 0),
    status                      VARCHAR(32) NOT NULL,
    message_id                  VARCHAR(35),
    message_created_at          TIMESTAMPTZ,
    resend_taken                BOOLEAN NOT NULL DEFAULT FALSE,
    uetr                        CHAR(36) NOT NULL,
    reason_code                 VARCHAR(4),
    reason                      VARCHAR(255),
    raw_pacs009                 TEXT,
    raw_mt202                   TEXT,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    version                     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_swift_institution_transfers_status ON swift_institution_transfers(status);

CREATE TABLE swift_institution_transfer_rejections (
    payment_id       UUID PRIMARY KEY,
    idempotency_key  VARCHAR(255),
    reason_code      VARCHAR(4) NOT NULL,
    reason           VARCHAR(255) NOT NULL,
    synthetic        BOOLEAN NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL
);

-- One claim per payment, shared by BOTH outcomes: the transfer insert and the rejection record each
-- INSERT here in the same transaction as their row and outbox event. A unique violation (payment id
-- or idempotency key) means the other path already won, and the loser writes nothing.
CREATE TABLE swift_institution_transfer_claims (
    payment_id       UUID PRIMARY KEY,
    idempotency_key  VARCHAR(255) UNIQUE,
    outcome          VARCHAR(10) NOT NULL CHECK (outcome IN ('TRANSFER', 'REJECTION')),
    created_at       TIMESTAMPTZ NOT NULL
);
