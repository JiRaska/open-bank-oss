CREATE TABLE IF NOT EXISTS swift_messages (
    id                         UUID PRIMARY KEY,
    idempotency_key            VARCHAR(255) NOT NULL UNIQUE,
    message_type               VARCHAR(10) NOT NULL,
    sender_bic                 VARCHAR(11) NOT NULL,
    receiver_bic               VARCHAR(11) NOT NULL,
    transaction_reference      VARCHAR(16) NOT NULL,
    related_reference          VARCHAR(16),
    value_date                 CHAR(8) NOT NULL,
    currency                   CHAR(3) NOT NULL,
    amount_minor_units         BIGINT NOT NULL,
    ordering_customer_account  VARCHAR(34),
    ordering_customer_name     VARCHAR(140),
    beneficiary_account        VARCHAR(34) NOT NULL,
    beneficiary_name           VARCHAR(140) NOT NULL,
    remittance_info            VARCHAR(140),
    charge_code                CHAR(3) NOT NULL DEFAULT 'SHA',
    priority                   VARCHAR(10) NOT NULL DEFAULT 'NORMAL',
    status                     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    raw_mt                     TEXT,
    ack_received_at            TIMESTAMPTZ,
    rejection_reason           TEXT,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_swift_status ON swift_messages(status);
CREATE INDEX idx_swift_sender ON swift_messages(sender_bic);
CREATE INDEX idx_swift_receiver ON swift_messages(receiver_bic);
